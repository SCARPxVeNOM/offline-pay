const { expect } = require("chai");
const { ethers } = require("hardhat");

// Build a voucher digest matching OfflineVault.voucherDigest exactly.
// Backend and Android must produce the same bytes.
function buildDigest(v, chainId, vaultAddr) {
  return ethers.solidityPackedKeccak256(
    ["bytes"],
    [ethers.AbiCoder.defaultAbiCoder().encode(
      ["address","address","uint256","uint256","uint256","bytes32","uint256","address"],
      [v.payer, v.merchant, v.amount, v.expiry, v.nonce, v.voucherId, chainId, vaultAddr]
    )]
  );
}

async function signVoucher(signer, v, chainId, vaultAddr) {
  const digest = buildDigest(v, chainId, vaultAddr);
  // EIP-191 personal_sign — signMessage prepends "\x19Ethereum Signed Message:\n32"
  const sig = await signer.signMessage(ethers.getBytes(digest));
  return sig;
}

describe("OfflineVault", function () {
  let vault, usdc, owner, payer, merchantA, merchantB, attacker;
  let chainId, vaultAddr;
  const ONE_USDC = 1_000_000n;

  beforeEach(async function () {
    [owner, payer, merchantA, merchantB, attacker] = await ethers.getSigners();
    const Mock = await ethers.getContractFactory("MockUSDC");
    usdc = await Mock.deploy(); await usdc.waitForDeployment();
    const Vault = await ethers.getContractFactory("OfflineVault");
    vault = await Vault.deploy(await usdc.getAddress()); await vault.waitForDeployment();
    chainId = Number((await ethers.provider.getNetwork()).chainId);
    vaultAddr = await vault.getAddress();

    await usdc.mint(payer.address, 100n * ONE_USDC);
    await usdc.connect(payer).approve(vaultAddr, 100n * ONE_USDC);
  });

  it("locks funds within cap", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    expect(await vault.lockedBalance(payer.address)).to.equal(5n * ONE_USDC);
  });

  it("rejects locks over cap", async function () {
    await expect(vault.connect(payer).lockFunds(6n * ONE_USDC))
      .to.be.revertedWith("exceeds cap");
  });

  it("settles a valid bearer voucher", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: ethers.ZeroAddress,
      amount: 2n * ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("v1")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);

    const before = await usdc.balanceOf(merchantA.address);
    await vault.connect(merchantA).settleVoucher(v, sig);
    expect(await usdc.balanceOf(merchantA.address)).to.equal(before + v.amount);
    expect(await vault.usedVouchers(v.voucherId)).to.equal(true);
  });

  it("blocks double-spend of same voucherId", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("dup")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await vault.connect(merchantA).settleVoucher(v, sig);
    await expect(vault.connect(merchantB).settleVoucher(v, sig))
      .to.be.revertedWith("already settled");
  });

  it("rejects expired voucher", async function () {
    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: 1, nonce: 1, voucherId: ethers.id("exp")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await expect(vault.connect(merchantA).settleVoucher(v, sig))
      .to.be.revertedWith("expired");
  });

  it("rejects bad signature", async function () {
    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("badsig")
    };
    const sig = await signVoucher(attacker, v, chainId, vaultAddr); // wrong signer
    await expect(vault.connect(merchantA).settleVoucher(v, sig))
      .to.be.revertedWith("bad signature");
  });

  it("enforces fixed-merchant binding", async function () {
    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: merchantA.address,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("fix")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await expect(vault.connect(merchantB).settleVoucher(v, sig))
      .to.be.revertedWith("wrong merchant");
    await expect(vault.connect(merchantA).settleVoucher(v, sig)).to.not.be.reverted;
  });

  it("enforces strictly increasing nonce", async function () {
    await vault.connect(payer).lockFunds(3n * ONE_USDC);
    const mk = (n, id) => ({
      payer: payer.address, merchant: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: n, voucherId: ethers.id(id)
    });
    const v1 = mk(5, "n5"); const s1 = await signVoucher(payer, v1, chainId, vaultAddr);
    const v0 = mk(3, "n3"); const s0 = await signVoucher(payer, v0, chainId, vaultAddr);
    await vault.connect(merchantA).settleVoucher(v1, s1);
    await expect(vault.connect(merchantB).settleVoucher(v0, s0))
      .to.be.revertedWith("stale nonce");
  });

  it("settles a batch", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const vs = [], sigs = [];
    for (let i = 1; i <= 3; i++) {
      const v = {
        payer: payer.address, merchant: ethers.ZeroAddress,
        amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
        nonce: i, voucherId: ethers.id("b"+i)
      };
      vs.push(v); sigs.push(await signVoucher(payer, v, chainId, vaultAddr));
    }
    await vault.connect(merchantA).settleBatch(vs, sigs);
    expect(await usdc.balanceOf(merchantA.address)).to.equal(3n * ONE_USDC);
  });

  it("unlock returns leftover funds", async function () {
    await vault.connect(payer).lockFunds(3n * ONE_USDC);
    await vault.connect(payer).unlock(2n * ONE_USDC);
    expect(await vault.lockedBalance(payer.address)).to.equal(ONE_USDC);
  });

  it("pause blocks new lock and settle", async function () {
    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    await vault.pause();
    await expect(vault.connect(payer).lockFunds(ONE_USDC))
      .to.be.revertedWithCustomError(vault, "EnforcedPause");
  });
});

const { expect } = require("chai");
const { ethers } = require("hardhat");

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
  return signer.signMessage(ethers.getBytes(digest));
}

describe("MerchantRegistry + device-keyed settlement", function () {
  let registry, vault, usdc, owner, payer, primary, devicePhone, deviceEsp32, attacker;
  let chainId, vaultAddr;
  const ONE_USDC = 1_000_000n;

  beforeEach(async function () {
    [owner, payer, primary, devicePhone, deviceEsp32, attacker] = await ethers.getSigners();

    const Mock = await ethers.getContractFactory("MockUSDC");
    usdc = await Mock.deploy(); await usdc.waitForDeployment();

    const Vault = await ethers.getContractFactory("OfflineVault");
    vault = await Vault.deploy(await usdc.getAddress()); await vault.waitForDeployment();

    const Registry = await ethers.getContractFactory("MerchantRegistry");
    registry = await Registry.deploy(); await registry.waitForDeployment();

    await vault.setMerchantRegistry(await registry.getAddress());

    chainId = Number((await ethers.provider.getNetwork()).chainId);
    vaultAddr = await vault.getAddress();

    await usdc.mint(payer.address, 100n * ONE_USDC);
    await usdc.connect(payer).approve(vaultAddr, 100n * ONE_USDC);
  });

  it("registers a merchant and authorizes devices", async function () {
    const id = await registry.idOf("chai-stall-mg-road");
    await registry.connect(primary).register("chai-stall-mg-road");
    expect(await registry.primaryOf(id)).to.equal(primary.address);

    await registry.connect(primary).authorizeDevice(id, devicePhone.address);
    await registry.connect(primary).authorizeDevice(id, deviceEsp32.address);
    expect(await registry.isAuthorized(id, devicePhone.address)).to.equal(true);

    const [resolvedId, resolvedPrimary] = await registry.resolveDevice(devicePhone.address);
    expect(resolvedId).to.equal(id);
    expect(resolvedPrimary).to.equal(primary.address);
  });

  it("blocks non-primary from authorizing devices", async function () {
    await registry.connect(primary).register("kirana");
    const id = await registry.idOf("kirana");
    await expect(registry.connect(attacker).authorizeDevice(id, devicePhone.address))
      .to.be.revertedWith("not primary");
  });

  it("revokes a lost device — and resolveDevice goes blank", async function () {
    await registry.connect(primary).register("dosa-stall");
    const id = await registry.idOf("dosa-stall");
    await registry.connect(primary).authorizeDevice(id, deviceEsp32.address);

    await registry.connect(primary).revokeDevice(id, deviceEsp32.address);
    const [rid, rp] = await registry.resolveDevice(deviceEsp32.address);
    expect(rid).to.equal(ethers.ZeroHash);
    expect(rp).to.equal(ethers.ZeroAddress);
  });

  it("rotates primary after phone-loss recovery", async function () {
    await registry.connect(primary).register("juice-bar");
    const id = await registry.idOf("juice-bar");
    await registry.connect(primary).setPrimary(id, owner.address);
    expect(await registry.primaryOf(id)).to.equal(owner.address);
    // old primary cannot authorize anymore
    await expect(registry.connect(primary).authorizeDevice(id, devicePhone.address))
      .to.be.revertedWith("not primary");
  });

  it("settles via device key — funds go to primary, not the device", async function () {
    await registry.connect(primary).register("samosa");
    const id = await registry.idOf("samosa");
    await registry.connect(primary).authorizeDevice(id, devicePhone.address);

    await vault.connect(payer).lockFunds(3n * ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: ethers.ZeroAddress,           // bearer
      amount: 2n * ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("dev-v1"),
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);

    const before = await usdc.balanceOf(primary.address);
    const beforeDev = await usdc.balanceOf(devicePhone.address);
    await vault.connect(devicePhone).settleVoucherAsDevice(v, sig);

    expect(await usdc.balanceOf(primary.address)).to.equal(before + v.amount);
    expect(await usdc.balanceOf(devicePhone.address)).to.equal(beforeDev); // device never holds funds
  });

  it("rejects settlement from a revoked device", async function () {
    await registry.connect(primary).register("paan");
    const id = await registry.idOf("paan");
    await registry.connect(primary).authorizeDevice(id, deviceEsp32.address);
    await registry.connect(primary).revokeDevice(id, deviceEsp32.address);

    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("revoked"),
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await expect(vault.connect(deviceEsp32).settleVoucherAsDevice(v, sig))
      .to.be.revertedWith("device not authorized");
  });

  it("device-keyed batch settles all vouchers to primary", async function () {
    await registry.connect(primary).register("batch-merchant");
    const id = await registry.idOf("batch-merchant");
    await registry.connect(primary).authorizeDevice(id, devicePhone.address);

    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const vs = [], sigs = [];
    for (let i = 1; i <= 3; i++) {
      const v = {
        payer: payer.address, merchant: ethers.ZeroAddress,
        amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
        nonce: i, voucherId: ethers.id("db"+i),
      };
      vs.push(v); sigs.push(await signVoucher(payer, v, chainId, vaultAddr));
    }
    await vault.connect(devicePhone).settleBatchAsDevice(vs, sigs);
    expect(await usdc.balanceOf(primary.address)).to.equal(3n * ONE_USDC);
  });

  it("reverts device settle if registry unset", async function () {
    const Vault = await ethers.getContractFactory("OfflineVault");
    const v2 = await Vault.deploy(await usdc.getAddress()); await v2.waitForDeployment();

    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("noreg"),
    };
    const sig = await signVoucher(payer, v, chainId, await v2.getAddress());
    await expect(v2.connect(devicePhone).settleVoucherAsDevice(v, sig))
      .to.be.revertedWith("registry unset");
  });
});

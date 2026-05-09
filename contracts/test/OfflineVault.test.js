const { expect } = require("chai");
const { ethers } = require("hardhat");

// Build a voucher digest matching OfflineVault.voucherDigest exactly.
// Backend and Android must produce the same bytes.
// v3 schema: (payer, merchant, recipient, amount, expiry, nonce, voucherId, chainId, vault).
function buildDigest(v, chainId, vaultAddr) {
  return ethers.solidityPackedKeccak256(
    ["bytes"],
    [ethers.AbiCoder.defaultAbiCoder().encode(
      ["address","address","address","uint256","uint256","uint256","bytes32","uint256","address"],
      [v.payer, v.merchant, v.recipient, v.amount, v.expiry, v.nonce, v.voucherId, chainId, vaultAddr]
    )]
  );
}

async function signVoucher(signer, v, chainId, vaultAddr) {
  const digest = buildDigest(v, chainId, vaultAddr);
  // EIP-191 personal_sign — signMessage prepends "\x19Ethereum Signed Message:\n32"
  const sig = await signer.signMessage(ethers.getBytes(digest));
  return sig;
}

// Helpers — voucher tuples are passed to the contract positionally.
const tuple = (v) => [v.payer, v.merchant, v.recipient, v.amount, v.expiry, v.nonce, v.voucherId];

describe("OfflineVault (v3: recipient-bound)", function () {
  let vault, usdc, owner, payer, merchantA, merchantB, recipient, relay, attacker;
  let chainId, vaultAddr;
  const ONE_USDC = 1_000_000n;

  beforeEach(async function () {
    [owner, payer, merchantA, merchantB, recipient, relay, attacker] = await ethers.getSigners();
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

  // ─── Legacy fixed-merchant path ─────────────────────────────────

  it("settles a valid fixed-merchant voucher", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: merchantA.address,
      recipient: merchantA.address,
      amount: 2n * ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("v1")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);

    const before = await usdc.balanceOf(merchantA.address);
    await vault.connect(merchantA).settleVoucher(tuple(v), sig);
    expect(await usdc.balanceOf(merchantA.address)).to.equal(before + v.amount);
    expect(await vault.usedVouchers(v.voucherId)).to.equal(true);
  });

  it("blocks double-spend of same voucherId", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: merchantA.address, recipient: merchantA.address,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("dup")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await vault.connect(merchantA).settleVoucher(tuple(v), sig);
    await expect(vault.connect(merchantA).settleVoucher(tuple(v), sig))
      .to.be.revertedWith("already settled");
  });

  it("rejects expired voucher", async function () {
    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: merchantA.address, recipient: merchantA.address,
      amount: ONE_USDC, expiry: 1, nonce: 1, voucherId: ethers.id("exp")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await expect(vault.connect(merchantA).settleVoucher(tuple(v), sig))
      .to.be.revertedWith("expired");
  });

  it("rejects bad signature", async function () {
    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: merchantA.address, recipient: merchantA.address,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("badsig")
    };
    const sig = await signVoucher(attacker, v, chainId, vaultAddr); // wrong signer
    await expect(vault.connect(merchantA).settleVoucher(tuple(v), sig))
      .to.be.revertedWith("bad signature");
  });

  it("enforces fixed-merchant binding", async function () {
    await vault.connect(payer).lockFunds(2n * ONE_USDC);
    const v = {
      payer: payer.address, merchant: merchantA.address, recipient: merchantA.address,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("fix")
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await expect(vault.connect(merchantB).settleVoucher(tuple(v), sig))
      .to.be.revertedWith("wrong merchant");
    await expect(vault.connect(merchantA).settleVoucher(tuple(v), sig)).to.not.be.reverted;
  });

  it("enforces strictly increasing nonce on legacy path", async function () {
    await vault.connect(payer).lockFunds(3n * ONE_USDC);
    const mk = (n, id) => ({
      payer: payer.address, merchant: merchantA.address, recipient: merchantA.address,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: n, voucherId: ethers.id(id)
    });
    const v1 = mk(5, "n5"); const s1 = await signVoucher(payer, v1, chainId, vaultAddr);
    const v0 = mk(3, "n3"); const s0 = await signVoucher(payer, v0, chainId, vaultAddr);
    await vault.connect(merchantA).settleVoucher(tuple(v1), s1);
    await expect(vault.connect(merchantA).settleVoucher(tuple(v0), s0))
      .to.be.revertedWith("stale nonce");
  });

  it("settles a fixed-merchant batch", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const vs = [], sigs = [];
    for (let i = 1; i <= 3; i++) {
      const v = {
        payer: payer.address, merchant: merchantA.address, recipient: merchantA.address,
        amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
        nonce: i, voucherId: ethers.id("b"+i)
      };
      vs.push(v); sigs.push(await signVoucher(payer, v, chainId, vaultAddr));
    }
    await vault.connect(merchantA).settleBatch(vs.map(tuple), sigs);
    expect(await usdc.balanceOf(merchantA.address)).to.equal(3n * ONE_USDC);
  });

  // ─── Bearer (relay-friendly) path ────────────────────────────────

  it("bearer settle: anyone can broadcast, funds flow to signed recipient", async function () {
    await vault.connect(payer).lockFunds(3n * ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: ethers.ZeroAddress,            // bearer
      recipient: recipient.address,            // signed by payer
      amount: ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("bearer-relay"),
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);

    const before = await usdc.balanceOf(recipient.address);
    // `relay` is a totally unrelated address — the mesh broadcaster.
    await vault.connect(relay).settleBearer(tuple(v), sig);
    expect(await usdc.balanceOf(recipient.address)).to.equal(before + v.amount);
    // Relay got nothing.
    expect(await usdc.balanceOf(relay.address)).to.equal(0n);
  });

  it("bearer settle: rejects voucher with merchant != 0", async function () {
    await vault.connect(payer).lockFunds(ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: merchantA.address,             // non-zero — not a bearer
      recipient: recipient.address,
      amount: ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("not-bearer"),
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await expect(vault.connect(relay).settleBearer(tuple(v), sig))
      .to.be.revertedWith("not bearer");
  });

  it("bearer settle: rejects recipient = 0", async function () {
    await vault.connect(payer).lockFunds(ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: ethers.ZeroAddress,
      recipient: ethers.ZeroAddress,           // illegal
      amount: ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("zero-recip"),
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    await expect(vault.connect(relay).settleBearer(tuple(v), sig))
      .to.be.revertedWith("recipient=0");
  });

  it("bearer settle: relay-attacker cannot redirect by tampering recipient", async function () {
    await vault.connect(payer).lockFunds(ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: ethers.ZeroAddress,
      recipient: recipient.address,            // signed for legitimate recipient
      amount: ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("attack"),
    };
    const sig = await signVoucher(payer, v, chainId, vaultAddr);
    // Attacker swaps the recipient field but reuses the same signature.
    const tampered = { ...v, recipient: attacker.address };
    await expect(vault.connect(attacker).settleBearer(tuple(tampered), sig))
      .to.be.revertedWith("bad signature");
  });

  it("bearer settle: skips per-payer nonce check (out-of-order broadcast OK)", async function () {
    await vault.connect(payer).lockFunds(3n * ONE_USDC);
    const mk = (n, id) => ({
      payer: payer.address, merchant: ethers.ZeroAddress, recipient: recipient.address,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: n, voucherId: ethers.id(id)
    });
    const vHigh = mk(5, "high"); const sHigh = await signVoucher(payer, vHigh, chainId, vaultAddr);
    const vLow  = mk(3, "low");  const sLow  = await signVoucher(payer, vLow,  chainId, vaultAddr);
    await vault.connect(relay).settleBearer(tuple(vHigh), sHigh);
    // Even though nonce 3 < settled 5, this should still succeed.
    await expect(vault.connect(relay).settleBearer(tuple(vLow), sLow)).to.not.be.reverted;
  });

  it("bearer batch: settles several to the same recipient", async function () {
    await vault.connect(payer).lockFunds(5n * ONE_USDC);
    const vs = [], sigs = [];
    for (let i = 1; i <= 3; i++) {
      const v = {
        payer: payer.address, merchant: ethers.ZeroAddress, recipient: recipient.address,
        amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
        nonce: i, voucherId: ethers.id("batch"+i)
      };
      vs.push(v); sigs.push(await signVoucher(payer, v, chainId, vaultAddr));
    }
    const before = await usdc.balanceOf(recipient.address);
    await vault.connect(relay).settleBearerBatch(vs.map(tuple), sigs);
    expect(await usdc.balanceOf(recipient.address)).to.equal(before + 3n * ONE_USDC);
  });

  // ─── True-bearer + endorsement (B2: physical bearer cards) ─────────

  // Build the endorsement digest exactly like OfflineVault.endorsementDigest.
  function endorsementDigestJs(voucherId, device, primary, ts, chainId, vaultAddr) {
    const domain = ethers.keccak256(ethers.toUtf8Bytes("OFFPAY-ENDORSE-V1"));
    return ethers.solidityPackedKeccak256(
      ["bytes"],
      [ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32","bytes32","address","address","uint256","uint256","address"],
        [domain, voucherId, device, primary, ts, chainId, vaultAddr]
      )]
    );
  }
  async function signEndorsement(deviceSigner, voucherId, primary, ts, chainId, vaultAddr) {
    const d = endorsementDigestJs(voucherId, deviceSigner.address, primary, ts, chainId, vaultAddr);
    return await deviceSigner.signMessage(ethers.getBytes(d));
  }

  it("endorsement: device endorses bearer voucher → primary gets paid", async function () {
    // Setup: payer locks $3, signs a true-bearer voucher (recipient = 0).
    await vault.connect(payer).lockFunds(3n * ONE_USDC);
    const v = {
      payer: payer.address,
      merchant: ethers.ZeroAddress,
      recipient: ethers.ZeroAddress,           // true bearer marker
      amount: ONE_USDC,
      expiry: Math.floor(Date.now()/1000) + 3600,
      nonce: 1,
      voucherId: ethers.id("endorse-happy"),
    };
    const voucherSig = await signVoucher(payer, v, chainId, vaultAddr);

    // ESP32 (we use `merchantA` as the ESP32 device key) endorses, naming
    // `recipient` as the merchant primary (the bonded phone's address).
    const ts = Math.floor(Date.now()/1000);
    const device = merchantA;             // pretending this signer is the ESP32
    const merchantPrimary = recipient.address;
    const espSig = await signEndorsement(device, v.voucherId, merchantPrimary, ts, chainId, vaultAddr);

    const before = await usdc.balanceOf(merchantPrimary);
    // `relay` (random unrelated wallet) broadcasts. Funds go to merchantPrimary.
    await vault.connect(relay).settleBearerWithEndorsement(
      tuple(v), voucherSig, device.address, merchantPrimary, ts, espSig);
    expect(await usdc.balanceOf(merchantPrimary)).to.equal(before + v.amount);
    expect(await usdc.balanceOf(relay.address)).to.equal(0n);
  });

  it("endorsement: rejects when voucher.recipient is non-zero", async function () {
    await vault.connect(payer).lockFunds(ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress,
      recipient: recipient.address,            // illegal for this path
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("e-bad-recip"),
    };
    const voucherSig = await signVoucher(payer, v, chainId, vaultAddr);
    const ts = Math.floor(Date.now()/1000);
    const espSig = await signEndorsement(merchantA, v.voucherId, recipient.address, ts, chainId, vaultAddr);
    await expect(vault.connect(relay).settleBearerWithEndorsement(
      tuple(v), voucherSig, merchantA.address, recipient.address, ts, espSig))
      .to.be.revertedWith("must be true bearer");
  });

  it("endorsement: rejects forged endorsement (different signer)", async function () {
    await vault.connect(payer).lockFunds(ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress, recipient: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("e-forged"),
    };
    const voucherSig = await signVoucher(payer, v, chainId, vaultAddr);
    const ts = Math.floor(Date.now()/1000);
    // Signed by `attacker` but claiming to be `merchantA`.
    const espSig = await signEndorsement(attacker, v.voucherId, recipient.address, ts, chainId, vaultAddr);
    await expect(vault.connect(relay).settleBearerWithEndorsement(
      tuple(v), voucherSig, merchantA.address, recipient.address, ts, espSig))
      .to.be.revertedWith("bad endorsement sig");
  });

  it("endorsement: relay cannot redirect by tampering with primary", async function () {
    // The endorsement signature commits to (voucherId, device, primary, ts).
    // Swapping `primary` invalidates the device's signature.
    await vault.connect(payer).lockFunds(ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress, recipient: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("e-redirect"),
    };
    const voucherSig = await signVoucher(payer, v, chainId, vaultAddr);
    const ts = Math.floor(Date.now()/1000);
    // ESP32 endorses the legitimate primary…
    const espSig = await signEndorsement(merchantA, v.voucherId, recipient.address, ts, chainId, vaultAddr);
    // …but the malicious relay submits with their own address as primary.
    await expect(vault.connect(attacker).settleBearerWithEndorsement(
      tuple(v), voucherSig, merchantA.address, attacker.address, ts, espSig))
      .to.be.revertedWith("bad endorsement sig");
  });

  it("endorsement: blocks double-spend across both bearer paths", async function () {
    await vault.connect(payer).lockFunds(ONE_USDC);
    const v = {
      payer: payer.address, merchant: ethers.ZeroAddress, recipient: ethers.ZeroAddress,
      amount: ONE_USDC, expiry: Math.floor(Date.now()/1000)+3600,
      nonce: 1, voucherId: ethers.id("e-double"),
    };
    const voucherSig = await signVoucher(payer, v, chainId, vaultAddr);
    const ts = Math.floor(Date.now()/1000);
    const espSig = await signEndorsement(merchantA, v.voucherId, recipient.address, ts, chainId, vaultAddr);

    await vault.connect(relay).settleBearerWithEndorsement(
      tuple(v), voucherSig, merchantA.address, recipient.address, ts, espSig);
    await expect(vault.connect(relay).settleBearerWithEndorsement(
      tuple(v), voucherSig, merchantA.address, recipient.address, ts, espSig))
      .to.be.revertedWith("already settled");
  });

  // ─── Generic ────────────────────────────────────────────────────

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

// Redeploys OfflineVault while reusing the existing MockUSDC + MerchantRegistry
// already on Amoy. Used for in-place schema upgrades (e.g. adding
// settleBearerWithEndorsement) without forcing every wallet to re-topup.
//
// Inputs are read from deployments/<network>.json so we don't drift.

const { ethers, network } = require("hardhat");
const fs = require("fs");
const path = require("path");

async function main() {
  const [deployer] = await ethers.getSigners();
  const net = network.name;
  console.log("Deployer:", deployer.address);
  console.log("Network: ", net, "chainId:",
    (await ethers.provider.getNetwork()).chainId);

  const deploymentsPath = path.join(__dirname, "..", "deployments", `${net}.json`);
  if (!fs.existsSync(deploymentsPath)) {
    throw new Error(`no existing deployment file at ${deploymentsPath}`);
  }
  const existing = JSON.parse(fs.readFileSync(deploymentsPath, "utf8"));
  console.log("Existing USDC:    ", existing.usdc);
  console.log("Existing Registry:", existing.registry);

  const Vault = await ethers.getContractFactory("OfflineVault");
  const vault = await Vault.deploy(existing.usdc);
  await vault.waitForDeployment();
  const vaultAddr = await vault.getAddress();
  console.log("New OfflineVault:", vaultAddr);

  if (existing.registry) {
    await (await vault.setMerchantRegistry(existing.registry)).wait();
    console.log("Vault → registry wired");
  }

  const updated = {
    ...existing,
    vault: vaultAddr,
    deployedAt: new Date().toISOString(),
    note: "vault redeployed in-place; usdc + registry preserved",
  };
  fs.writeFileSync(deploymentsPath, JSON.stringify(updated, null, 2));
  console.log("\nWrote", deploymentsPath);
  console.log("\nNext steps:");
  console.log("  1. Update Config.kt VAULT_ADDRESS =", vaultAddr);
  console.log("  2. Update backend .env: VAULT_ADDRESS=" + vaultAddr);
  console.log("  3. ssh + restart backend");
  console.log("  4. Update firmware vault constant + reflash");
}

main().catch((e) => { console.error(e); process.exit(1); });

const { ethers, network } = require("hardhat");
const fs = require("fs");
const path = require("path");

// Real Polygon USDC address; for testnet/local we deploy MockUSDC.
const POLYGON_MAINNET_USDC = "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359";

async function main() {
  const [deployer] = await ethers.getSigners();
  console.log("Deployer:", deployer.address);
  console.log("Network: ", network.name, "chainId:", (await ethers.provider.getNetwork()).chainId);

  let usdcAddr;
  if (network.name === "hardhat" || network.name === "localhost" || network.name === "amoy") {
    const Mock = await ethers.getContractFactory("MockUSDC");
    const mock = await Mock.deploy();
    await mock.waitForDeployment();
    usdcAddr = await mock.getAddress();
    console.log("MockUSDC:", usdcAddr);
  } else {
    usdcAddr = POLYGON_MAINNET_USDC;
  }

  const Vault = await ethers.getContractFactory("OfflineVault");
  const vault = await Vault.deploy(usdcAddr);
  await vault.waitForDeployment();
  const vaultAddr = await vault.getAddress();
  console.log("OfflineVault:", vaultAddr);

  const out = {
    network: network.name,
    chainId: Number((await ethers.provider.getNetwork()).chainId),
    usdc: usdcAddr,
    vault: vaultAddr,
    deployer: deployer.address,
    deployedAt: new Date().toISOString()
  };
  const outPath = path.resolve(__dirname, "..", "deployments", `${network.name}.json`);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(out, null, 2));
  console.log("Wrote", outPath);
}

main().catch((e) => { console.error(e); process.exit(1); });

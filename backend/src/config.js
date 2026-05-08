import "dotenv/config";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

function loadDeployment() {
  // Auto-discover the most recent deployment if env vars aren't set.
  const deployDir = path.resolve(process.cwd(), "..", "contracts", "deployments");
  if (!fs.existsSync(deployDir)) return null;
  const files = fs.readdirSync(deployDir).filter(f => f.endsWith(".json"));
  if (!files.length) return null;
  const candidate = files
    .map(f => ({ f, t: fs.statSync(path.join(deployDir, f)).mtimeMs }))
    .sort((a, b) => b.t - a.t)[0].f;
  return JSON.parse(fs.readFileSync(path.join(deployDir, candidate), "utf8"));
}

const deployment = loadDeployment();

export const config = {
  port: Number(process.env.PORT || 4000),
  rpcUrl: process.env.CHAIN_RPC_URL || "http://127.0.0.1:8545",
  chainId: Number(process.env.CHAIN_ID || deployment?.chainId || 31337),
  vault:   process.env.VAULT_ADDRESS || deployment?.vault,
  usdc:    process.env.USDC_ADDRESS  || deployment?.usdc,
  backendKey: process.env.BACKEND_PRIVATE_KEY ||
    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
  dbPath: process.env.DB_PATH || "./data/offlinepay.db",
  // Server secret for the keybackup pepper. Derive a stable per-user salt
  // so a blob dump alone is useless without server cooperation. A random
  // dev fallback is generated and persisted in `data/.pepper` on first run;
  // PRODUCTION must set KEYBACKUP_PEPPER_SECRET via env.
  keybackupPepperSecret: process.env.KEYBACKUP_PEPPER_SECRET || (() => {
    const p = path.resolve(path.dirname(process.env.DB_PATH || "./data/offlinepay.db"), ".pepper");
    if (fs.existsSync(p)) return fs.readFileSync(p, "utf8").trim();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const v = crypto.randomBytes(32).toString("hex");
    fs.writeFileSync(p, v);
    return v;
  })(),
};

if (!config.vault || !config.usdc) {
  console.warn(
    "[config] VAULT_ADDRESS / USDC_ADDRESS not set and no deployment file found.\n" +
    "         Run `cd contracts && npm run deploy:local` first or set env vars."
  );
}

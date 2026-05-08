import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";
import { config } from "./config.js";

fs.mkdirSync(path.dirname(config.dbPath), { recursive: true });
export const db = new DatabaseSync(config.dbPath);
db.exec("PRAGMA journal_mode = WAL");

db.exec(`
CREATE TABLE IF NOT EXISTS customers (
  address TEXT PRIMARY KEY,
  upi_id  TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS vouchers (
  voucher_id TEXT PRIMARY KEY,
  payer      TEXT NOT NULL,
  merchant   TEXT NOT NULL,
  amount     TEXT NOT NULL,
  expiry     INTEGER NOT NULL,
  nonce      INTEGER NOT NULL,
  signature  TEXT NOT NULL,
  status     TEXT NOT NULL DEFAULT 'issued',
  issued_at  INTEGER NOT NULL,
  redeemed_at INTEGER,
  settled_tx TEXT
);

CREATE INDEX IF NOT EXISTS idx_vouchers_payer    ON vouchers(payer);
CREATE INDEX IF NOT EXISTS idx_vouchers_merchant ON vouchers(merchant);
CREATE INDEX IF NOT EXISTS idx_vouchers_status   ON vouchers(status);

CREATE TABLE IF NOT EXISTS keybackups (
  user_id        TEXT PRIMARY KEY,
  address        TEXT NOT NULL,
  salt_b64       TEXT NOT NULL,
  iv_b64         TEXT NOT NULL,
  ciphertext_b64 TEXT NOT NULL,
  iterations     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS topups (
  id TEXT PRIMARY KEY,
  customer TEXT NOT NULL,
  upi_ref  TEXT,
  amount_inr_paise INTEGER NOT NULL,
  amount_usdc      TEXT NOT NULL,
  status TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
`);

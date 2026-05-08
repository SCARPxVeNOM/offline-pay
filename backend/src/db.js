import Database from "better-sqlite3";
import fs from "node:fs";
import path from "node:path";
import { config } from "./config.js";

fs.mkdirSync(path.dirname(config.dbPath), { recursive: true });
export const db = new Database(config.dbPath);
db.pragma("journal_mode = WAL");

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
  status     TEXT NOT NULL DEFAULT 'issued',  -- issued | redeemed | settled | expired
  issued_at  INTEGER NOT NULL,
  redeemed_at INTEGER,
  settled_tx TEXT
);

CREATE INDEX IF NOT EXISTS idx_vouchers_payer    ON vouchers(payer);
CREATE INDEX IF NOT EXISTS idx_vouchers_merchant ON vouchers(merchant);
CREATE INDEX IF NOT EXISTS idx_vouchers_status   ON vouchers(status);

CREATE TABLE IF NOT EXISTS topups (
  id TEXT PRIMARY KEY,
  customer TEXT NOT NULL,
  upi_ref  TEXT,
  amount_inr_paise INTEGER NOT NULL,
  amount_usdc      TEXT NOT NULL,
  status TEXT NOT NULL,  -- pending | locked | failed
  created_at INTEGER NOT NULL
);
`);

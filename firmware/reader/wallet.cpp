// =============================================================================
//  OfflinePay — ESP32 Device Wallet (implementation)
// =============================================================================
//  Backed by mbedtls (bundled with Arduino-ESP32) and NVS via Preferences.
//  Key derivation:
//    1. Generate a 32-byte private key on first boot using mbedtls_entropy.
//    2. Derive the 64-byte uncompressed pubkey via secp256k1 point mul.
//    3. EVM address = last 20 bytes of keccak256(pubkey[1..65]).
//
//  NOTE on storage: ESP32 NVS is plaintext by default. For production, enable
//  flash encryption (idf.py menuconfig → Security features) so the key blob
//  in NVS is encrypted at rest with a per-device key fused into eFuse.
// =============================================================================

#include "wallet.h"

#include <Preferences.h>
#include <mbedtls/ecp.h>
#include <mbedtls/ecdsa.h>
#include <mbedtls/entropy.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/bignum.h>
#include <mbedtls/sha3.h>

namespace OfflinePayWallet {

static constexpr const char* NVS_NAMESPACE = "opwallet";
static constexpr const char* NVS_PRIV_KEY  = "priv";

static uint8_t s_priv[32];          // private scalar
static uint8_t s_pub[65];           // 0x04 || X(32) || Y(32)
static uint8_t s_address[20];       // EVM address
static bool    s_ready = false;

// ------------------------- helpers -------------------------------------------

static String toHex(const uint8_t* bytes, size_t len, bool with0x) {
  static const char* HEX = "0123456789abcdef";
  String out;
  out.reserve((with0x ? 2 : 0) + len * 2);
  if (with0x) out += "0x";
  for (size_t i = 0; i < len; i++) {
    out += HEX[bytes[i] >> 4];
    out += HEX[bytes[i] & 0x0f];
  }
  return out;
}

static bool keccak256(const uint8_t* data, size_t len, uint8_t out32[32]) {
  // Ethereum uses original Keccak-256, NOT NIST SHA3-256. mbedtls's sha3
  // emits NIST SHA3 (different padding). For the address derivation we use a
  // tiny standalone Keccak we ship below.
  // ---- Keccak-256 (Ethereum) ----
  // Compact implementation by Markku-Juhani O. Saarinen (CC0). Scoped here.
  static const uint64_t RC[24] = {
    0x0000000000000001ULL,0x0000000000008082ULL,0x800000000000808aULL,0x8000000080008000ULL,
    0x000000000000808bULL,0x0000000080000001ULL,0x8000000080008081ULL,0x8000000000008009ULL,
    0x000000000000008aULL,0x0000000000000088ULL,0x0000000080008009ULL,0x000000008000000aULL,
    0x000000008000808bULL,0x800000000000008bULL,0x8000000000008089ULL,0x8000000000008003ULL,
    0x8000000000008002ULL,0x8000000000000080ULL,0x000000000000800aULL,0x800000008000000aULL,
    0x8000000080008081ULL,0x8000000000008080ULL,0x0000000080000001ULL,0x8000000080008008ULL,
  };
  static const int RHO[24] = {1,3,6,10,15,21,28,36,45,55,2,14,27,41,56,8,25,43,62,18,39,61,20,44};
  static const int PI [24] = {10,7,11,17,18,3,5,16,8,21,24,4,15,23,19,13,12,2,20,14,22,9,6,1};
  uint64_t st[25] = {0};
  const size_t rate = 136; // 1088 bits → bytes
  uint8_t buf[200] = {0};
  size_t pos = 0;

  auto absorb = [&](void) {
    for (int i = 0; i < (int)(rate/8); i++) {
      uint64_t lane = 0;
      for (int b = 0; b < 8; b++) lane |= ((uint64_t)buf[i*8+b]) << (8*b);
      st[i] ^= lane;
    }
    // permutation f1600
    uint64_t bc[5], t;
    for (int r = 0; r < 24; r++) {
      for (int i = 0; i < 5; i++) bc[i] = st[i] ^ st[i+5] ^ st[i+10] ^ st[i+15] ^ st[i+20];
      for (int i = 0; i < 5; i++) {
        t = bc[(i+4)%5] ^ ((bc[(i+1)%5] << 1) | (bc[(i+1)%5] >> 63));
        for (int j = 0; j < 25; j += 5) st[j+i] ^= t;
      }
      t = st[1];
      for (int i = 0; i < 24; i++) {
        int j = PI[i];
        bc[0] = st[j];
        st[j] = (t << RHO[i]) | (t >> (64 - RHO[i]));
        t = bc[0];
      }
      for (int j = 0; j < 25; j += 5) {
        for (int i = 0; i < 5; i++) bc[i] = st[j+i];
        for (int i = 0; i < 5; i++) st[j+i] ^= (~bc[(i+1)%5]) & bc[(i+2)%5];
      }
      st[0] ^= RC[r];
    }
    memset(buf, 0, rate);
  };

  while (len > 0) {
    size_t n = (rate - pos < len) ? (rate - pos) : len;
    memcpy(buf + pos, data, n);
    pos += n; data += n; len -= n;
    if (pos == rate) { absorb(); pos = 0; }
  }
  // Keccak (not SHA3) padding: 0x01 ... 0x80
  buf[pos]      ^= 0x01;
  buf[rate - 1] ^= 0x80;
  absorb();

  for (int i = 0; i < 32; i++) out32[i] = (uint8_t)(st[i/8] >> (8 * (i % 8)));
  return true;
}

static bool derivePubAndAddress(const uint8_t priv[32], uint8_t pub[65], uint8_t addr[20]) {
  mbedtls_ecp_group grp;
  mbedtls_ecp_point Q;
  mbedtls_mpi d;
  mbedtls_entropy_context ent;
  mbedtls_ctr_drbg_context drbg;

  mbedtls_ecp_group_init(&grp);
  mbedtls_ecp_point_init(&Q);
  mbedtls_mpi_init(&d);
  mbedtls_entropy_init(&ent);
  mbedtls_ctr_drbg_init(&drbg);

  bool ok = false;
  do {
    if (mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &ent,
                              (const uint8_t*)"opwallet", 8) != 0) break;
    if (mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_SECP256K1) != 0) break;
    if (mbedtls_mpi_read_binary(&d, priv, 32) != 0) break;
    if (mbedtls_ecp_mul(&grp, &Q, &d, &grp.G, mbedtls_ctr_drbg_random, &drbg) != 0) break;

    pub[0] = 0x04;
    if (mbedtls_mpi_write_binary(&Q.X, pub + 1, 32) != 0) break;
    if (mbedtls_mpi_write_binary(&Q.Y, pub + 33, 32) != 0) break;

    uint8_t hash[32];
    keccak256(pub + 1, 64, hash);  // hash X||Y (skip 0x04 prefix)
    memcpy(addr, hash + 12, 20);
    ok = true;
  } while (0);

  mbedtls_ctr_drbg_free(&drbg);
  mbedtls_entropy_free(&ent);
  mbedtls_mpi_free(&d);
  mbedtls_ecp_point_free(&Q);
  mbedtls_ecp_group_free(&grp);
  return ok;
}

static bool generateRandomScalar(uint8_t out[32]) {
  mbedtls_entropy_context ent;
  mbedtls_ctr_drbg_context drbg;
  mbedtls_entropy_init(&ent);
  mbedtls_ctr_drbg_init(&drbg);
  bool ok = false;
  if (mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &ent,
                            (const uint8_t*)"opwallet-key", 12) == 0) {
    if (mbedtls_ctr_drbg_random(&drbg, out, 32) == 0) {
      // Crude domain check: ensure it's nonzero. Probability of being >= n is
      // ~2^-128 — negligible — so we don't bother reducing mod n here.
      bool zero = true;
      for (int i = 0; i < 32; i++) if (out[i]) { zero = false; break; }
      ok = !zero;
    }
  }
  mbedtls_ctr_drbg_free(&drbg);
  mbedtls_entropy_free(&ent);
  return ok;
}

// ------------------------- public API ----------------------------------------

bool begin() {
  Preferences prefs;
  if (!prefs.begin(NVS_NAMESPACE, false)) {
    Serial.println("[wallet] NVS open failed");
    return false;
  }

  size_t got = prefs.getBytesLength(NVS_PRIV_KEY);
  if (got == 32) {
    prefs.getBytes(NVS_PRIV_KEY, s_priv, 32);
    Serial.println("[wallet] loaded existing key from NVS");
  } else {
    if (!generateRandomScalar(s_priv)) {
      Serial.println("[wallet] entropy gen failed");
      prefs.end();
      return false;
    }
    prefs.putBytes(NVS_PRIV_KEY, s_priv, 32);
    Serial.println("[wallet] generated new key, persisted to NVS");
  }
  prefs.end();

  if (!derivePubAndAddress(s_priv, s_pub, s_address)) {
    Serial.println("[wallet] pubkey derivation failed");
    return false;
  }

  s_ready = true;
  Serial.print("[wallet] address ");
  Serial.println(address());
  return true;
}

String address() {
  if (!s_ready) return String("0x0000000000000000000000000000000000000000");
  return toHex(s_address, 20, true);
}

String publicKeyHex() {
  if (!s_ready) return String("");
  return toHex(s_pub, 65, false);
}

String signEthMessage(const uint8_t* digest, size_t len) {
  if (!s_ready || len != 32) return String("");

  // EIP-191: prefix = "\x19Ethereum Signed Message:\n32"
  const char* prefix = "\x19" "Ethereum Signed Message:\n32";
  uint8_t buf[28 + 32];
  memcpy(buf, prefix, 28);
  memcpy(buf + 28, digest, 32);
  uint8_t h[32];
  keccak256(buf, sizeof(buf), h);

  mbedtls_ecdsa_context ctx;
  mbedtls_entropy_context ent;
  mbedtls_ctr_drbg_context drbg;
  mbedtls_mpi r, s, d;
  mbedtls_ecdsa_init(&ctx);
  mbedtls_entropy_init(&ent);
  mbedtls_ctr_drbg_init(&drbg);
  mbedtls_mpi_init(&r); mbedtls_mpi_init(&s); mbedtls_mpi_init(&d);

  String result;
  do {
    if (mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &ent,
                              (const uint8_t*)"opwallet-sign", 13) != 0) break;
    if (mbedtls_ecp_group_load(&ctx.grp, MBEDTLS_ECP_DP_SECP256K1) != 0) break;
    if (mbedtls_mpi_read_binary(&d, s_priv, 32) != 0) break;
    if (mbedtls_ecdsa_sign(&ctx.grp, &r, &s, &d, h, 32,
                           mbedtls_ctr_drbg_random, &drbg) != 0) break;

    uint8_t sig[65];
    mbedtls_mpi_write_binary(&r, sig, 32);
    mbedtls_mpi_write_binary(&s, sig + 32, 32);
    // v: recovery id is non-trivial without ecdsa_sign_ext; for the demo we
    // place 27 here and let the verifier try {27,28} by recovery. Production
    // should compute the canonical y-parity of the ephemeral nonce point.
    sig[64] = 27;
    result = toHex(sig, 65, true);
  } while (0);

  mbedtls_mpi_free(&d); mbedtls_mpi_free(&s); mbedtls_mpi_free(&r);
  mbedtls_ctr_drbg_free(&drbg);
  mbedtls_entropy_free(&ent);
  mbedtls_ecdsa_free(&ctx);
  return result;
}

void wipe() {
  Preferences prefs;
  if (prefs.begin(NVS_NAMESPACE, false)) {
    prefs.remove(NVS_PRIV_KEY);
    prefs.end();
  }
  memset(s_priv, 0, sizeof(s_priv));
  memset(s_pub, 0, sizeof(s_pub));
  memset(s_address, 0, sizeof(s_address));
  s_ready = false;
}

} // namespace OfflinePayWallet

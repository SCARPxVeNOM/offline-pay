package com.offlinepay.wallet

import android.app.Application

/// Process-scoped owner of the mesh stack. Activities going through
/// onPause→onResume stop/start advertising in <100ms otherwise, which
/// Nearby Connections rejects (STATUS_ALREADY_ADVERTISING from the prior
/// session that hasn't fully torn down). Holding one acquire here keeps
/// the advertise+discover loop running for the lifetime of the process,
/// so transitions like Home→Receive are now invisible to mesh peers.
///
/// Activities still acquire/release for symmetry, but the refcount never
/// drops to 0, so mesh.start() / mesh.stop() are called exactly once each
/// (on process up / down).
class OffpayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WalletMesh.acquire(this)
    }
}

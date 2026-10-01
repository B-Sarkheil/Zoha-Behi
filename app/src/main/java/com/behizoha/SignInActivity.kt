package com.behi.zoha

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * No login is needed any more (fixed B2 key).
 * This class stays only because it is the launcher activity in the manifest.
 */
class SignInActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, FolderActivity::class.java))
        finish()
    }
}

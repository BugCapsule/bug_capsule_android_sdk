package dev.bugcapsule.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        ShopApp.navigation.attach(host.navController)
    }
}

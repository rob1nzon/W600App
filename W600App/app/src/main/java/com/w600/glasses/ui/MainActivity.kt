package com.w600.glasses.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.w600.glasses.R
import com.w600.glasses.databinding.ActivityMainBinding
import com.w600.glasses.model.ConnectionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private lateinit var navController: androidx.navigation.NavController

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user enabled BT */ }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = navHost.navController

        val appBarConfig = AppBarConfiguration(
            setOf(
                R.id.scanFragment,
                R.id.deviceFragment,
                R.id.mediaFragment,
                R.id.previewFragment,
                R.id.aiCaptureFragment,
                R.id.logFragment
            )
        )
        setupActionBarWithNavController(navController, appBarConfig)
        binding.bottomNav.setupWithNavController(navController)

        requestBtPermissions()
        observeEvents()
        observeConnectionState()
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        supportActionBar?.subtitle = "Connected: ${state.deviceName}"
                        binding.bottomNav.menu.findItem(R.id.deviceFragment)?.isEnabled = true
                        binding.bottomNav.menu.findItem(R.id.mediaFragment)?.isEnabled = true
                        binding.bottomNav.menu.findItem(R.id.previewFragment)?.isEnabled = true
                        binding.bottomNav.menu.findItem(R.id.aiCaptureFragment)?.isEnabled = true
                    }
                    is ConnectionState.Disconnected -> {
                        supportActionBar?.subtitle = "Disconnected"
                    }
                    is ConnectionState.Connecting -> {
                        supportActionBar?.subtitle = "Connecting to ${state.deviceName}…"
                    }
                    is ConnectionState.Error -> {
                        supportActionBar?.subtitle = "Error"
                        Snackbar.make(binding.root, "Error: ${state.message}", Snackbar.LENGTH_LONG).show()
                        if (navController.currentDestination?.id != R.id.logFragment) {
                            navController.navigate(R.id.logFragment)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            viewModel.events.collect { msg ->
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            viewModel.aiTriggers.collect {
                if (navController.currentDestination?.id != R.id.aiCaptureFragment) {
                    navController.navigate(R.id.aiCaptureFragment)
                }
            }
        }
    }

    private fun requestBtPermissions() {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (btAdapter?.isEnabled == false) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_disconnect -> {
            viewModel.disconnect()
            true
        }
        R.id.action_refresh -> {
            viewModel.refresh()
            true
        }
        R.id.action_logs -> {
            if (navController.currentDestination?.id != R.id.logFragment) {
                navController.navigate(R.id.logFragment)
            }
            true
        }
        R.id.action_sync_time -> {
            viewModel.syncTime()
            Toast.makeText(this, "Time synced", Toast.LENGTH_SHORT).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

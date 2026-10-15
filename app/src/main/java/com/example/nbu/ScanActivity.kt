package com.example.nbu

import android.content.pm.PackageManager
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.example.nbu.databinding.ActivityScanBinding
import com.example.nbu.util.StatusBarUtil
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class ScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        val binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.brand_primary))
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            val torch = binding.toolbar.menu.add(R.string.torch_on).setCheckable(true)
            torch.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            binding.barcodeScanner.setTorchListener(object : DecoratedBarcodeView.TorchListener {
                override fun onTorchOn() {
                    torch.isChecked = true
                    torch.setTitle(R.string.torch_off)
                }

                override fun onTorchOff() {
                    torch.isChecked = false
                    torch.setTitle(R.string.torch_on)
                }
            })
            binding.toolbar.setOnMenuItemClickListener {
                if (torch.isChecked) binding.barcodeScanner.setTorchOff() else binding.barcodeScanner.setTorchOn()
                true
            }
        }
        return binding.barcodeScanner
    }
}

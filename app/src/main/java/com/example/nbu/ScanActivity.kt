package com.example.nbu

import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.nbu.databinding.ActivityScanBinding
import com.example.nbu.util.StatusBarUtil
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScanBinding
    private lateinit var capture: CaptureManager
    private lateinit var album: MenuItem
    private var readingImage = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        readingImage = true
        album.isEnabled = false
        binding.barcodeScanner.pause()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = maxOf(info.size.width, info.size.height) / 2048f
                        if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
                    }
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true
                        ))
                    } finally {
                        bitmap.recycle()
                    }
                }
                setResult(RESULT_OK, CaptureManager.resultIntent(BarcodeResult(result, null), null))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderException) {
                Toast.makeText(this@ScanActivity, R.string.image_no_code, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ScanActivity, getString(R.string.image_read_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
            } finally {
                readingImage = false
                album.isEnabled = true
                if (!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) binding.barcodeScanner.resume()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.header))
        binding.toolbar.setNavigationOnClickListener { finish() }
        album = binding.toolbar.menu.add(R.string.scan_album)
        setMenuTitle(album, R.string.scan_album)
        album.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        album.setOnMenuItemClickListener {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            true
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            val torch = binding.toolbar.menu.add(0, 0, 0, null).setCheckable(true)
            setMenuTitle(torch, R.string.torch_on)
            torch.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            binding.barcodeScanner.setTorchListener(object : DecoratedBarcodeView.TorchListener {
                override fun onTorchOn() {
                    torch.isChecked = true
                    setMenuTitle(torch, R.string.torch_off)
                }

                override fun onTorchOff() {
                    torch.isChecked = false
                    setMenuTitle(torch, R.string.torch_on)
                }
            })
            torch.setOnMenuItemClickListener {
                if (torch.isChecked) binding.barcodeScanner.setTorchOff() else binding.barcodeScanner.setTorchOn()
                true
            }
        }
        capture = CaptureManager(this, binding.barcodeScanner)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
        if (readingImage) binding.barcodeScanner.pause()
    }

    override fun onPause() {
        capture.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        capture.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        binding.barcodeScanner.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    private fun setMenuTitle(item: MenuItem, titleRes: Int) {
        item.title = SpannableString(getString(titleRes)).apply {
            setSpan(ForegroundColorSpan(getColor(R.color.on_header)), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }
}

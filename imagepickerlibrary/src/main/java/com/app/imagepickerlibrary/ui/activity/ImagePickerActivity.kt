package com.app.imagepickerlibrary.ui.activity

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.app.imagepickerlibrary.R
import com.app.imagepickerlibrary.addFragment
import com.app.imagepickerlibrary.createImageFile
import com.app.imagepickerlibrary.databinding.ActivityImagePickerBinding
import com.app.imagepickerlibrary.dateFormatForTakePicture
import com.app.imagepickerlibrary.dispatchTakePictureIntent
import com.app.imagepickerlibrary.getBooleanAttribute
import com.app.imagepickerlibrary.getColorAttribute
import com.app.imagepickerlibrary.getModel
import com.app.imagepickerlibrary.model.Folder
import com.app.imagepickerlibrary.model.Image
import com.app.imagepickerlibrary.model.PickerConfig
import com.app.imagepickerlibrary.model.PickerType
import com.app.imagepickerlibrary.registerActivityResult
import com.app.imagepickerlibrary.replaceFragment
import com.app.imagepickerlibrary.ui.fragment.FolderFragment
import com.app.imagepickerlibrary.ui.fragment.ImageFragment
import com.app.imagepickerlibrary.util.isAtLeast13
import com.app.imagepickerlibrary.viewmodel.ImagePickerViewModel
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ImagePickerActivity to display all the images folder wise.
 * After picking images from here the results are sent back to ImagePicker class
 * and the caller is notified via callback
 */
class ImagePickerActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var binding: ActivityImagePickerBinding
    private val viewModel: ImagePickerViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory(application)
    }
    private lateinit var pickerConfig: PickerConfig
    private var fileUri: Uri? = null
    private var toolBarTitle = ""
    private lateinit var backPressedCallback: OnBackPressedCallback
    private var openCameraAfterPermission: Boolean = false
    private var disableInteraction: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColorAttribute(R.attr.ssStatusBarColor)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            getBooleanAttribute(R.attr.ssStatusBarLightMode)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_image_picker)
        pickerConfig = intent.getModel() ?: PickerConfig.defaultPicker()
        viewModel.updatePickerConfig(pickerConfig)
        setUI()
        openCameraAfterPermission = pickerConfig.pickerType == PickerType.CAMERA
        pickImage()
        addObserver()
        enableEdgeToEdge(binding.toolbar.root)
    }

    private fun setUI() {
        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                onBackPressEvent()
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        updateToolBarTitle(pickerConfig.pickerTitle)
        binding.toolbar.apply {
            clickListener = this@ImagePickerActivity
            imageCameraButton.isVisible = pickerConfig.showCameraIconInGallery
            imageDoneButton.isVisible =
                pickerConfig.isDoneIcon && pickerConfig.allowMultipleSelection
            textDone.isVisible = !pickerConfig.isDoneIcon && pickerConfig.allowMultipleSelection
        }
    }

    private fun addObserver() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.selectedFolder.collect { folder ->
                        openFolder(folder)
                    }
                }
                launch {
                    viewModel.completeSelection.collect { complete ->
                        if (complete) {
                            selectImages()
                        }
                    }
                }
                launch {
                    viewModel.updateImageCount.collect {
                        updateSelectCount()
                    }
                }
                launch {
                    viewModel.disableInteraction.collect {
                        disableInteraction = it
                        binding.progressIndicator.isVisible = it
                    }
                }
            }
        }
    }


    override fun onClick(view: View) {
        if (disableInteraction) {
            return
        }
        when (view.id) {
            R.id.image_back_button -> {
                onBackPressedDispatcher.onBackPressed()
            }
            R.id.image_camera_button -> {
                showCamera()
            }
            R.id.image_done_button, R.id.text_done -> {
                selectImages()
            }
        }
    }

    private fun pickImage() {
        if (openCameraAfterPermission) {
            showCamera()
        } else {
            showGallery()
        }
    }

    private fun updateToolBarTitle(title: String) {
        toolBarTitle = title
        updateSelectCount()
    }

    private fun updateSelectCount() {
        if (pickerConfig.showCountInToolBar && pickerConfig.allowMultipleSelection) {
            binding.toolbar.textTitle.text =
                getString(
                    R.string.str_selected_image_toolbar,
                    toolBarTitle,
                    viewModel.getSelectedImages().size,
                    pickerConfig.maxPickCount
                )
        } else {
            binding.toolbar.textTitle.text = toolBarTitle
        }
    }

    private fun showCamera() {
        fileUri = dispatchTakePictureIntent(onGetImageFromCameraActivityResult)
    }

    /**
     * For Android 13+ (API level 33), the Photo Picker API is now the preferred method to access media files.
     * For Android 12L and below, we need READ_EXTERNAL_STORAGE permission.
     * This approach avoids the need for READ_MEDIA permissions on Android 13+.
     */
    private fun showGallery() {
        // For Android 13+, we don't need permissions with Photo Picker API
        // For below Android 13, we need READ_EXTERNAL_STORAGE permission
        if (checkForPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            replaceFragment(getInitialFragment())
            viewModel.fetchImagesFromMediaStore()
        } else {
            // Always request permission for pre-Android 13 devices
            openCameraAfterPermission = false
            askPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun getInitialFragment(): Fragment {
        return if (pickerConfig.showFolders) {
            FolderFragment.newInstance()
        } else {
            ImageFragment.newInstance()
        }
    }

    private fun checkForPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPermission(vararg permission: String) {
        permissionResult.launch(arrayOf(*permission))
    }

    private fun openFolder(folder: Folder) {
        updateToolBarTitle(folder.bucketName)
        backPressedCallback.isEnabled = true
        addFragment(ImageFragment.newInstance(folder.bucketId))
    }

    private fun onBackPressEvent() {
        val fragment = supportFragmentManager.findFragmentById(R.id.container_view)
        if (fragment != null && fragment is ImageFragment) {
            updateToolBarTitle(pickerConfig.pickerTitle)
            backPressedCallback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private val permissionResult =
        activityResultRegistry.register(
            "Permission",
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            result?.let { mutableMap ->
                if (mutableMap.entries.all { entry -> entry.value }) {
                    pickImage()
                }
            }
        }

    private val onCropImageActivityResult =
        registerActivityResult("CropImage", { createSingleSelectionResult(null) }) {
            createSingleSelectionResult(it.data?.let { intent -> UCrop.getOutput(intent) })
        }

    private val onGetImageFromCameraActivityResult =
        registerActivityResult("Camera", errorCallback = { createSingleSelectionResult(null) }) {
            fileUri?.let { uri -> checkForCropping(uri) } ?: createSingleSelectionResult(null)
        }

    private fun checkForCropping(imageUri: Uri) {
        if (pickerConfig.openCropOptions || pickerConfig.compressImage || pickerConfig.aspectRatio != null) {
            val date =
                SimpleDateFormat(dateFormatForTakePicture, Locale.getDefault()).format(Date())
            val imageFile = createImageFile(date)
            val cropIntent = UCrop.of(imageUri, Uri.fromFile(imageFile))
                .withOptions(getUCropOptions()).getIntent(this)
            onCropImageActivityResult.launch(cropIntent)
        } else {
            createSingleSelectionResult(imageUri)
        }
    }

    /**
     * Cropping option for the crop screen. Changing colors and setting ui controls.
     */
    private fun getUCropOptions(): UCrop.Options {
        pickerConfig.aspectRatio?.let { aspectRatio ->
            return UCrop.Options().apply {
                withAspectRatio(aspectRatio.x, aspectRatio.y)
            }
        }
        return UCrop.Options().apply {
            setFreeStyleCropEnabled(pickerConfig.openCropOptions)
            setHideBottomControls(!pickerConfig.openCropOptions)
            setToolbarColor(getColorAttribute(R.attr.ssUCropToolbarColor))
            setStatusBarColor(getColorAttribute(R.attr.ssUCropStatusBarColor))
            setToolbarWidgetColor(getColorAttribute(R.attr.ssUCropToolbarWidgetColor))
            setActiveControlsWidgetColor(getColorAttribute(R.attr.ssUCropActiveControlWidgetColor))
            if (pickerConfig.compressImage) {
                setCompressionQuality(pickerConfig.compressQuality)
            }
        }
    }

    private fun selectImages() {
        val selectedImages = viewModel.getSelectedImages()
        if (selectedImages.isEmpty()) {
            finish()
        }
        if (pickerConfig.allowMultipleSelection) {
            checkForCompression(selectedImages)
        } else {
            checkForCropping(selectedImages.first().uri)
        }
    }

    private fun checkForCompression(selectedImages: List<Image>) {
        if (pickerConfig.compressImage) {
            lifecycleScope.launch {
                val compressedImage = viewModel.compressImage(selectedImages)
                createMultipleSelectionResult(compressedImage)
            }
        } else {
            createMultipleSelectionResult(selectedImages)
        }
    }

    private fun createMultipleSelectionResult(selectedImages: List<Image>) {
        val intent = Intent()
        if (selectedImages.size == 1) {
            intent.data = selectedImages.first().uri
        } else if (selectedImages.size > 1) {
            val clipData = ClipData.newUri(contentResolver, "ClipData", selectedImages.first().uri)
            selectedImages.takeLast(selectedImages.size - 1)
                .forEach { clipData.addItem(ClipData.Item(it.uri)) }
            intent.clipData = clipData
        }
        sendResult(intent)
    }

    private fun createSingleSelectionResult(uri: Uri?) {
        val intent = Intent()
        intent.data = uri
        sendResult(intent)
    }

    private fun sendResult(intent: Intent) {
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        onCropImageActivityResult.unregister()
        onGetImageFromCameraActivityResult.unregister()
        super.onDestroy()
    }

    //If you are using custom theming and need to change the status bar color,
    // it may not work unless you specify a particular view object, like a toolbar.
    private fun enableEdgeToEdge(view: View?) {
        view?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { view, windowInsets ->
                val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(
                    systemBarInsets.left,
                    systemBarInsets.top,
                    systemBarInsets.right,
                    0
                )
                windowInsets
            }
        }
    }
}

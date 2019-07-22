/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver
import com.google.android.apps.muzei.render.ArtworkSizeLiveData
import com.google.android.apps.muzei.render.ContentUriImageLoader
import com.google.android.apps.muzei.render.SwitchingPhotosDone
import com.google.android.apps.muzei.render.SwitchingPhotosInProgress
import com.google.android.apps.muzei.render.SwitchingPhotosLiveData
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.room.sendAction
import com.google.android.apps.muzei.settings.AboutActivity
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.AnimatedMuzeiLoadingSpinnerView
import com.google.android.apps.muzei.util.PanScaleProxyView
import com.google.android.apps.muzei.util.makeCubicGradientScrimDrawable
import com.google.android.apps.muzei.widget.showWidgetPreview
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.BuildConfig.LEGACY_AUTHORITY
import net.nurik.roman.muzei.R

object ArtDetailOpenLiveData : MutableLiveData<Boolean>()

class ArtDetailFragment : Fragment(), (Boolean) -> Unit {

    companion object {
        private const val KEY_IMAGE_VIEW_STATE = "IMAGE_VIEW_STATE"
        private val SOURCE_ACTION_IDS = intArrayOf(
                R.id.source_action_1,
                R.id.source_action_2,
                R.id.source_action_3,
                R.id.source_action_4,
                R.id.source_action_5,
                R.id.source_action_6,
                R.id.source_action_7,
                R.id.source_action_8,
                R.id.source_action_9,
                R.id.source_action_10)
    }

    private var currentViewportId = 0
    private var wallpaperAspectRatio: Float = 0f
    private var artworkAspectRatio: Float = 0f

    private val providerObserver = Observer<Provider?> { provider ->
        val supportsNextArtwork = provider?.supportsNextArtwork == true
        nextButton.isVisible = supportsNextArtwork
    }

    @SuppressLint("Range")
    private val artworkObserver = Observer<Artwork?> { currentArtwork ->
        var titleFont = R.font.alegreya_sans_black
        var bylineFont = R.font.alegreya_sans_medium
        if (MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT == currentArtwork?.metaFont) {
            titleFont = R.font.alegreya_black_italic
            bylineFont = R.font.alegreya_italic
        }

        titleView.typeface = ResourcesCompat.getFont(requireContext(), titleFont)
        titleView.text = currentArtwork?.title

        bylineView.typeface = ResourcesCompat.getFont(requireContext(), bylineFont)
        bylineView.text = currentArtwork?.byline

        val attribution = currentArtwork?.attribution
        if (attribution?.isNotEmpty() == true) {
            attributionView.text = attribution
            attributionView.isVisible = true
        } else {
            attributionView.isGone = true
        }

        metadataView.setOnClickListener {
            val context = requireContext()
            lifecycleScope.launch {
                FirebaseAnalytics.getInstance(context).logEvent("artwork_info_open", bundleOf(
                        FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
                currentArtworkLiveData.value?.openArtworkInfo(context)
            }
        }

        if (backgroundImageContainer.isVisible) {
            lifecycleScope.launch {
                val nextId = (backgroundImageContainer.displayedChild + 1) % 2
                val orientation = withContext(Dispatchers.IO) {
                    ContentUriImageLoader(requireContext().contentResolver,
                            MuzeiContract.Artwork.CONTENT_URI).getRotation()
                }
                val backgroundImage = backgroundImageContainer[nextId]
                        as SubsamplingScaleImageView
                backgroundImage.orientation = orientation
                backgroundImage.setImage(ImageSource.uri(MuzeiContract.Artwork.CONTENT_URI),
                        backgroundImageViewState)
                backgroundImageViewState = null
                // Set the image to visible since SubsamplingScaleImageView does some of
                // its processing in onDraw()
                backgroundImage.isVisible = true
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            val commands = context?.run {
                currentArtwork?.getCommands(this) ?: run {
                    if (currentProviderLiveData.value?.authority == LEGACY_AUTHORITY) {
                        listOf(UserCommand(
                                MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK,
                                        getString(R.string.action_next_artwork)))
                    } else {
                        listOf()
                    }
                }
            } ?: return@launch
            val activity = activity ?: return@launch
            overflowSourceActionMap.clear()
            overflowMenu.menu.clear()
            activity.menuInflater.inflate(R.menu.muzei_overflow,
                    overflowMenu.menu)
            overflowMenu.menu.findItem(R.id.action_always_dark)?.isChecked =
                    MuzeiApplication.getAlwaysDark(activity)
            commands.take(SOURCE_ACTION_IDS.size).forEachIndexed { i, action ->
                overflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action.id)
                val menuItem = overflowMenu.menu.add(0, SOURCE_ACTION_IDS[i],
                        0, action.title)
                if (action.id == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK &&
                        currentProviderLiveData.value?.authority == LEGACY_AUTHORITY) {
                    menuItem.setIcon(R.drawable.ic_skip)
                    menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
        }
        showFakeLoading = false
        updateLoadingSpinnerVisibility()
    }

    private var guardViewportChangeListener: Boolean = false
    private var deferResetViewport: Boolean = false

    private val showBackgroundImage by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && requireActivity().isInMultiWindowMode
    }
    private val metadataSlideDistance by lazy {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
    }

    private lateinit var containerView: View
    private lateinit var overflowMenu: ActionMenuView
    private val overflowSourceActionMap = SparseIntArray()
    private lateinit var scrimView: View
    private lateinit var chromeContainerView: View
    private lateinit var metadataView: View
    private lateinit var loadingContainerView: View
    private lateinit var loadingIndicatorView: AnimatedMuzeiLoadingSpinnerView
    private lateinit var nextButton: View
    private lateinit var titleView: TextView
    private lateinit var bylineView: TextView
    private lateinit var attributionView: TextView
    private lateinit var backgroundImageContainer: ViewAnimator
    private lateinit var panScaleProxyView: PanScaleProxyView
    private var loadingSpinnerShown = false
    private var showFakeLoading = false
    private var showChrome = true
    private var backgroundImageViewState: ImageViewState? = null
    private val currentProviderLiveData: LiveData<Provider?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).providerDao().currentProvider
    }
    private val currentArtworkLiveData: LiveData<Artwork?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).artworkDao().currentArtwork
    }

    private var unsetNextFakeLoading: Job? = null
    private var showLoadingSpinner: Job? = null

    init {
        lifecycleScope.launchWhenResumed {
            NewWallpaperNotificationReceiver.markNotificationRead(requireContext())
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        containerView = inflater.inflate(R.layout.art_detail_fragment, container, false)

        chromeContainerView = containerView.findViewById(R.id.chrome_container)
        showHideChrome(true)

        return containerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        scrimView = view.findViewById(R.id.art_detail_scrim)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scrimView.background = makeCubicGradientScrimDrawable(Gravity.TOP, 0x44)
        }

        val scrimColor = resources.getInteger(R.integer.scrim_channel_color)
        chromeContainerView.background = makeCubicGradientScrimDrawable(Gravity.BOTTOM, 0xAA,
                scrimColor, scrimColor, scrimColor)

        metadataView = view.findViewById(R.id.metadata)

        containerView.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0
            animateChromeVisibility(visible)
        }

        titleView = view.findViewById(R.id.title)
        bylineView = view.findViewById(R.id.byline)
        attributionView = view.findViewById(R.id.attribution)

        overflowMenu = view.findViewById(R.id.overflow_menu_view)
        overflowMenu.overflowIcon = ContextCompat.getDrawable(requireContext(),
                R.drawable.ic_overflow)
        overflowMenu.setOnMenuItemClickListener { menuItem ->
            val context = context ?: return@setOnMenuItemClickListener false
            val id = overflowSourceActionMap.get(menuItem.itemId)
            if (id > 0) {
                currentArtworkLiveData.value?.run {
                    GlobalScope.launch {
                        if (id == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                            FirebaseAnalytics.getInstance(context).logEvent("next_artwork", bundleOf(
                                    FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
                        } else {
                            FirebaseAnalytics.getInstance(context).logEvent(
                                    FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                                    FirebaseAnalytics.Param.ITEM_ID to id,
                                    FirebaseAnalytics.Param.ITEM_NAME to menuItem.title,
                                    FirebaseAnalytics.Param.ITEM_CATEGORY to "actions",
                                    FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
                        }
                        sendAction(context, id)
                    }
                }
                return@setOnMenuItemClickListener true
            }

            return@setOnMenuItemClickListener when (menuItem.itemId) {
                R.id.action_gestures -> {
                    FirebaseAnalytics.getInstance(context).logEvent("gestures_open", null)
                    findNavController().navigate(ArtDetailFragmentDirections.gestures())
                    true
                }
                R.id.action_always_dark -> {
                    val alwaysDark = !menuItem.isChecked
                    menuItem.isChecked = alwaysDark
                    FirebaseAnalytics.getInstance(context).logEvent("always_dark", bundleOf(
                            FirebaseAnalytics.Param.CONTENT_TYPE to alwaysDark.toString()))
                    MuzeiApplication.setAlwaysDark(context, alwaysDark)
                    true
                }
                R.id.action_about -> {
                    FirebaseAnalytics.getInstance(context).logEvent("about_open", null)
                    startActivity(Intent(context, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        nextButton = view.findViewById(R.id.next_button)
        nextButton.setOnClickListener {
            FirebaseAnalytics.getInstance(requireContext()).logEvent("next_artwork", bundleOf(
                    FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
            ProviderManager.getInstance(requireContext()).nextArtwork()
            showFakeLoading()
        }
        TooltipCompat.setTooltipText(nextButton, nextButton.contentDescription)

        backgroundImageViewState = savedInstanceState?.getSerializable(
                KEY_IMAGE_VIEW_STATE) as ImageViewState?
        backgroundImageContainer = view.findViewById(R.id.background_image_container)
        backgroundImageContainer.isVisible = showBackgroundImage
        backgroundImageContainer.children.forEachIndexed { index, img ->
            val backgroundImage = img as SubsamplingScaleImageView
            backgroundImage.apply {
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP)
                setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoaded() {
                        // Only update the displayedChild when the image has finished loading
                        backgroundImageContainer.displayedChild = index
                    }
                })
                setOnClickListener {
                    showChrome = !showChrome
                    animateChromeVisibility(showChrome)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setOnLongClickListener {
                        lifecycleScope.launch {
                            showWidgetPreview(requireContext().applicationContext)
                        }
                        true
                    }
                }
            }
        }

        panScaleProxyView = view.findViewById(R.id.pan_scale_proxy)
        panScaleProxyView.apply {
            // Don't show the PanScaleProxyView when the background image is visible
            isVisible = !showBackgroundImage
            setMaxZoom(5)
            onViewportChanged = {
                if (!guardViewportChangeListener) {
                    ArtDetailViewport.setViewport(
                            currentViewportId, panScaleProxyView.currentViewport, true)
                }
            }
            onSingleTapUp = {
                val window = activity?.window
                if (window != null) {
                    showHideChrome(window.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                onLongPress = {
                    lifecycleScope.launch {
                        showWidgetPreview(requireContext().applicationContext)
                    }
                }
            }
        }

        loadingContainerView = view.findViewById(R.id.image_loading_container)
        loadingIndicatorView = view.findViewById(R.id.image_loading_indicator)

        WallpaperSizeLiveData.observe(viewLifecycleOwner) { size ->
            wallpaperAspectRatio = if (size.height > 0) {
                size.width * 1f / size.height
            } else {
                panScaleProxyView.width * 1f / panScaleProxyView.height
            }
            resetProxyViewport()
        }

        ArtworkSizeLiveData.observe(viewLifecycleOwner) { size ->
            artworkAspectRatio = size.width * 1f / size.height
            resetProxyViewport()
        }

        ArtDetailViewport.addObserver(this)

        SwitchingPhotosLiveData.observe(viewLifecycleOwner) { switchingPhotos ->
            currentViewportId = switchingPhotos.viewportId
            panScaleProxyView.panScaleEnabled = switchingPhotos is SwitchingPhotosDone
            // Process deferred artwork size change when done switching
            if (switchingPhotos is SwitchingPhotosDone && deferResetViewport) {
                resetProxyViewport()
            }
        }

        currentProviderLiveData.observe(viewLifecycleOwner, providerObserver)
        currentArtworkLiveData.observe(viewLifecycleOwner, artworkObserver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (view != null) {
            val backgroundImage =
                    backgroundImageContainer[backgroundImageContainer.displayedChild]
                    as SubsamplingScaleImageView
            outState.putSerializable(KEY_IMAGE_VIEW_STATE, backgroundImage.state)
        }
    }

    override fun onStart() {
        super.onStart()
        ArtDetailOpenLiveData.value = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ArtDetailViewport.removeObserver(this)
    }

    private fun showHideChrome(show: Boolean) {
        requireActivity().window.decorView.apply {
            var flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            flags = flags or if (show) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
            flags = flags or (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            if (!show) {
                flags = flags or (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE)
            }
            systemUiVisibility = flags
        }
    }

    private fun animateChromeVisibility(visible: Boolean) {
        scrimView.visibility = View.VISIBLE
        scrimView.animate()
                .alpha(if (visible) 1f else 0f)
                .setDuration(200)
                .withEndAction {
                    if (!visible) {
                        scrimView.visibility = View.GONE
                    }
                }

        chromeContainerView.isVisible = true
        chromeContainerView.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else metadataSlideDistance)
                .setDuration(200)
                .withEndAction {
                    if (!visible) {
                        chromeContainerView.isGone = true
                    }
                }
    }

    private fun resetProxyViewport() {
        if (wallpaperAspectRatio == 0f || artworkAspectRatio == 0f) {
            return
        }

        deferResetViewport = false
        if (SwitchingPhotosLiveData.value is SwitchingPhotosInProgress) {
            deferResetViewport = true
            return
        }

        panScaleProxyView.relativeAspectRatio = artworkAspectRatio / wallpaperAspectRatio
    }

    override fun invoke(isFromUser: Boolean) {
        if (!isFromUser) {
            guardViewportChangeListener = true
            panScaleProxyView.setViewport(ArtDetailViewport.getViewport(currentViewportId))
            guardViewportChangeListener = false
        }
    }

    override fun onStop() {
        super.onStop()
        overflowMenu.hideOverflowMenu()
        ArtDetailOpenLiveData.value = false
    }

    private fun showFakeLoading() {
        showFakeLoading = true
        // Show a loading spinner for up to 10 seconds. When new artwork is loaded,
        // the loading spinner will go away.
        updateLoadingSpinnerVisibility()
        unsetNextFakeLoading?.cancel()
        unsetNextFakeLoading = lifecycleScope.launch(Dispatchers.Main) {
            delay(10000)
            showFakeLoading = false
            updateLoadingSpinnerVisibility()
        }
        updateLoadingSpinnerVisibility()
    }

    private fun updateLoadingSpinnerVisibility() {
        if (showFakeLoading != loadingSpinnerShown) {
            loadingSpinnerShown = showFakeLoading
            showLoadingSpinner?.cancel()?.also {
                showLoadingSpinner = null
            }
            if (showFakeLoading) {
                this.showLoadingSpinner = lifecycleScope.launch(Dispatchers.Main) {
                    delay(700)
                    loadingIndicatorView.start()
                    loadingContainerView.isVisible = true
                    loadingContainerView.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .withEndAction(null)
                }
            } else {
                loadingContainerView.animate()
                        .alpha(0f)
                        .setDuration(1000)
                        .withEndAction {
                            loadingContainerView.isGone = true
                            loadingIndicatorView.stop()
                        }
            }
        }
    }
}

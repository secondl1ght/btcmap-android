package map

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import element.ElementFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.btcmap.R
import org.btcmap.databinding.FragmentMapBinding
import org.koin.androidx.viewmodel.ext.android.sharedViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import search.SearchResultModel

class MapFragment : Fragment() {

    companion object {
        private const val DEFAULT_MAP_ZOOM = 12f
    }

    private val model: MapModel by viewModel()

    private val searchResultModel: SearchResultModel by sharedViewModel()

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val elementFragment by lazy {
        childFragmentManager.findFragmentById(R.id.elementFragment) as ElementFragment
    }

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<*>

    private var backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            onBackPressed()
        }
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                model.onLocationPermissionGranted()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                model.onLocationPermissionGranted()
            }
            else -> {}
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        Configuration.getInstance().load(
            requireContext(),
            PreferenceManager.getDefaultSharedPreferences(requireContext()),
        )

        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.apply {
            inflateMenu(R.menu.map)

            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_add -> {
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.data = Uri.parse("https://wiki.openstreetmap.org/wiki/How_to_contribute")
                        startActivity(intent)
                    }

                    R.id.action_donate -> {
                        findNavController().navigate(MapFragmentDirections.actionMapFragmentToDonationFragment())
                    }

                    R.id.action_search -> {
                        lifecycleScope.launch {
                            val action = MapFragmentDirections.actionMapFragmentToSearchFragment(
                                model.userLocation.value.latitude.toString(),
                                model.userLocation.value.longitude.toString(),
                            )

                            findNavController().navigate(action)
                        }
                    }
                }

                true
            }
        }

        binding.map.apply {
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            setMultiTouchControls(true)
            addLocationOverlay()
            addCancelSelectionOverlay()
            addViewportListener()
        }

        model.invalidateMarkersCache()

        bottomSheetBehavior = BottomSheetBehavior.from(binding.elementDetails)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.addSlideCallback()

        viewLifecycleOwner.lifecycleScope.launch {
            val elementDetailsToolbar = getElementDetailsToolbar()
            bottomSheetBehavior.peekHeight = elementDetailsToolbar.height

            elementDetailsToolbar.setOnClickListener {
                if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                } else {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED)
                }
            }
        }

        model.selectedElement.onEach {
            if (it != null) {
                getElementDetailsToolbar()
                elementFragment.setElement(it)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.fab.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestLocationPermissions()
                return@setOnClickListener
            }

            val mapController = binding.map.controller
            mapController.setZoom(DEFAULT_MAP_ZOOM.toDouble())
            mapController.setCenter(model.userLocation.value)
        }

        searchResultModel.element.filterNotNull().onEach {
            val mapController = binding.map.controller
            mapController.setZoom(DEFAULT_MAP_ZOOM.toDouble())
            val startPoint = GeoPoint(it.lat, it.lon)
            mapController.setCenter(startPoint)
            model.selectElement(it, true)
            searchResultModel.element.update { null }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        var elementsOverlay: RadiusMarkerClusterer? = null

        viewLifecycleOwner.lifecycleScope.launch {
            model.visibleElements.collectLatest { elementWithMarkers ->
                if (elementsOverlay != null) {
                    binding.map.overlays.remove(elementsOverlay)
                }

                elementsOverlay = RadiusMarkerClusterer(requireContext())
                val clusterIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_cluster)!!
                val pinSizePx =
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
                elementsOverlay!!.setIcon(clusterIcon.toBitmap(pinSizePx, pinSizePx))

                elementWithMarkers.forEach {
                    val marker = Marker(binding.map)
                    marker.position = GeoPoint(it.element.lat, it.element.lon)
                    marker.icon = it.marker

                    marker.setOnMarkerClickListener { _, _ ->
                        model.selectElement(it.element, false)
                        true
                    }

                    elementsOverlay!!.add(marker)
                }

                binding.map.overlays.add(elementsOverlay)
                binding.map.invalidate()
            }
        }

        var ignoreNextLocation = false

        model.mapBoundingBox.value?.let {
            ignoreNextLocation = true

            viewLifecycleOwner.lifecycleScope.launch {
                while (binding.map.getIntrinsicScreenRect(null).height() == 0) {
                    delay(10)
                }

                binding.map.zoomToBoundingBox(model.mapBoundingBox.value, false)
            }
        }

        model.moveToLocation.onEach {
            if (ignoreNextLocation) {
                ignoreNextLocation = false
                return@onEach
            }

            val mapController = binding.map.controller
            mapController.setZoom(DEFAULT_MAP_ZOOM.toDouble())
            mapController.setCenter(it)
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        lifecycleScope.launchWhenResumed {
            val snack = Snackbar.make(
                binding.root,
                "",
                Snackbar.LENGTH_INDEFINITE,
            )

            model.syncMessage.collectLatest {
                snack.setText(it)

                if (it.isNotBlank()) {
                    snack.show()
                } else {
                    snack.dismiss()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.syncElements()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun requestLocationPermissions() {
        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun onBackPressed() {
        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            requireActivity().finish()
        }
    }

    private fun MapView.addLocationOverlay() {
        val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), this)
        locationOverlay.enableMyLocation()
        binding.map.overlays += locationOverlay
    }

    private fun MapView.addCancelSelectionOverlay() {
        overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                model.selectElement(null, false)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false
            }
        })
    }

    private fun MapView.addViewportListener() {
        addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                model.setMapViewport(binding.map.boundingBox)
                return false
            }

            override fun onZoom(event: ZoomEvent?) = false
        })
    }

    private fun BottomSheetBehavior<*>.addSlideCallback() {
        addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {

            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                binding.fab.isVisible = slideOffset < 0.5f
                (childFragmentManager.findFragmentById(R.id.elementFragment) as ElementFragment).setScrollProgress(
                    slideOffset
                )
            }
        })
    }

    private suspend fun getElementDetailsToolbar(): Toolbar {
        while (elementFragment.view == null
            || elementFragment.view!!.findViewById<View>(R.id.toolbar)!!.height == 0
        ) {
            delay(10)
        }

        return elementFragment.view?.findViewById(R.id.toolbar)!!
    }
}
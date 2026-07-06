package com.streamflixreborn.streamflix.fragments.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSearchTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.VoiceRecognitionHelper
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.IptvProvider

class SearchTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentSearchTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }
    private var isGlobalSearchChecked: Boolean = false
    private var currentGridColumns: Int = 1

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->

                if (movie.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == movie.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, movie.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToMovie(id = movie.id)
                )
            }
            onTvShowClickListener = { tvShow ->

                if (tvShow.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == tvShow.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, tvShow.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToTvShow(
                        id = tvShow.id,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                    )
                )
            }
        }
    }

    private lateinit var voiceHelper: VoiceRecognitionHelper

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->

                when (state) {
                    is State.Searching, is State.GlobalSearching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is State.SearchingMore -> appAdapter.isLoading = true
                    is State.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.SuccessGlobalSearching -> {
                        displayGlobalSearch(state.providerResults)
                        appAdapter.isLoading = false
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.search(viewModel.query)
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.search(viewModel.query) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    viewModel.search(viewModel.query)
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                                binding.vgvSearch.visibility = View.INVISIBLE
                                binding.wheelKeyboard.nextFocusDownId = binding.isLoading.btnIsLoadingRetry.id
                                binding.isLoading.btnIsLoadingRetry.nextFocusUpId = binding.wheelKeyboard.id
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }

    private fun submitSearch(): Boolean {
        val query = binding.wheelKeyboard.getText()

        if (isGlobalSearchChecked) {
            if (query.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
                return true
            }
            val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
            viewModel.searchGlobal(query, currentLanguage)
        } else {
            viewModel.search(query)
        }
        return true
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.wheelKeyboard.hintText = getString(hintStringRes)

        binding.llGlobalSearch.nextFocusUpId = binding.wheelKeyboard.id
        binding.vgvSearch.nextFocusUpId = binding.llGlobalSearch.id

        binding.llGlobalSearch.setOnClickListener {
            isGlobalSearchChecked = !isGlobalSearchChecked
            binding.ivGlobalSearchSwitch.setImageResource(
                if (isGlobalSearchChecked) R.drawable.ic_switch_on else R.drawable.ic_switch_off
            )
        }

        binding.wheelKeyboard.apply {
            onTextChanged = { query ->
                // Keep the existing SearchViewModel query source single: the wheel writes
                // through submit/search actions instead of maintaining a parallel provider path.
                if (query.isEmpty() && viewModel.query.isNotEmpty()) {
                    viewModel.search("")
                }
            }
            onSearch = { submitSearch() }
            onDone = { submitSearch() }
            onVoiceSearch = { if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck() }
        }

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.wheelKeyboard.alpha = 1f
                binding.wheelKeyboard.setText(query)
                viewModel.search(query)
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.wheelKeyboard.alpha = 1f
            },
            onListeningStateChanged = { isListening ->
                binding.wheelKeyboard.alpha = if (isListening) 0.55f else 1f
            }
        )

        binding.vgvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.search_spacing).toInt())
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    child?.itemView?.nextFocusUpId =
                        if (position in 0 until currentGridColumns) binding.llGlobalSearch.id
                        else View.NO_ID
                }
            })
        }

        binding.wheelKeyboard.requestFocus()
    }

    private fun focusSearchContent(): Boolean {
        val hasResults = appAdapter.itemCount > 0 && binding.vgvSearch.visibility == View.VISIBLE
        return when {
            hasResults -> {
                binding.vgvSearch.requestFocus()
            }
            binding.llGlobalSearch.visibility == View.VISIBLE -> {
                binding.llGlobalSearch.requestFocus()
            }
            else -> false
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        currentGridColumns = if (viewModel.query == "") 5 else 6
        binding.vgvSearch.setNumColumns(currentGridColumns)

        appAdapter.submitList(list.onEach {
            when (it) {
                is Genre -> it.itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayGlobalSearch(providerResults: List<ProviderResult>) {
        val categories = providerResults.map { providerResult ->
            val headerTitle = when (val state = providerResult.state) {
                is ProviderResult.State.Loading -> "${providerResult.provider.name} - ${getString(R.string.searching)}"
                is ProviderResult.State.Error -> "${providerResult.provider.name} - ${getString(R.string.search_error)}"
                is ProviderResult.State.Success -> {
                    val count = state.results.size
                    val resultText = if (count == 1) getString(R.string.result) else getString(R.string.results)
                    "${providerResult.provider.name} - $count $resultText"
                }
            }

            val items = (providerResult.state as? ProviderResult.State.Success)?.results?.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            } ?: emptyList()

            Category(name = headerTitle, list = items).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
        }

        currentGridColumns = 1
        binding.vgvSearch.setNumColumns(currentGridColumns) // La lista de categorías es una sola columna vertical
        appAdapter.submitList(categories)
        appAdapter.setOnLoadMoreListener(null)
    }
}

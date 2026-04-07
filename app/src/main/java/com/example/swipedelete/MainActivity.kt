package com.example.swipedelete

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import coil.decode.VideoFrameDecoder
import com.example.swipedelete.ui.theme.SwipeDeleteTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val imageLoader = ImageLoader.Builder(applicationContext)
                .components { add(VideoFrameDecoder.Factory()) }
                .build()

            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                SwipeDeleteTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding).background(Color(0xFF121212))) {
                            MainScreen()
                        }
                    }
                }
            }
        }
    }
}

data class MediaAsset(
    val uri: Uri,
    val dateString: String,
    val flatIndex: Int
)

fun loadMedia(context: Context, isVideo: Boolean): List<MediaAsset> {
    val mediaList = mutableListOf<MediaAsset>()
    val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
    val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
    val dateFormat = java.text.SimpleDateFormat("yyyy년 M월 d일", java.util.Locale.getDefault())

    context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        var index = 0
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val dateAddedSec = cursor.getLong(dateColumn)
            val uri = ContentUris.withAppendedId(collection, id)
            val dateString = dateFormat.format(java.util.Date(dateAddedSec * 1000))
            mediaList.add(MediaAsset(uri, dateString, index))
            index++
        }
    }
    return mediaList
}

enum class AppView { Dashboard, SwipeReview }
enum class DashboardSection { Photos, Videos }

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }

    var photoAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var videoAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }

    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var currentSection by remember { mutableStateOf(DashboardSection.Photos) }

    val trashUris = remember { mutableStateListOf<Uri>() }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            trashUris.clear()
            isRefreshing = true
            refreshTrigger++
        }
    }

    var currentView by remember { mutableStateOf(AppView.Dashboard) }
    var startingIndex by remember { mutableIntStateOf(0) }

    // NEW: 사진과 비디오 탭 각각의 스크롤 위치를 기억하는 상태 저장소
    val photoGridState = rememberLazyGridState()
    val videoGridState = rememberLazyGridState()
    // NEW: 리뷰 화면에서 복귀했을 때 찾아갈 타겟 인덱스
    var lastViewedIndex by remember { mutableIntStateOf(-1) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasPermission = permissions.values.any { it == true }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    LaunchedEffect(hasPermission, refreshTrigger) {
        if (hasPermission) {
            if (refreshTrigger == 0) isLoading = true
            photoAssets = loadMedia(context, isVideo = false)
            videoAssets = loadMedia(context, isVideo = true)
            isRefreshing = false
            if (refreshTrigger == 0) isLoading = false
        }
    }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Please grant media permissions.", color = Color.White, fontSize = 18.sp)
        }
    } else if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF4CAF50))
        }
    } else {
        when (currentView) {
            AppView.Dashboard -> {
                PhotoDashboardScreen(
                    photoAssets = photoAssets,
                    videoAssets = videoAssets,
                    trashUris = trashUris,
                    deleteLauncher = deleteLauncher,
                    isRefreshing = isRefreshing,
                    currentSection = currentSection,
                    gridState = if (currentSection == DashboardSection.Photos) photoGridState else videoGridState, // 상태 전달
                    lastViewedIndex = lastViewedIndex, // 스크롤 타겟 인덱스 전달
                    onScrollComplete = { lastViewedIndex = -1 }, // 스크롤 완료 후 초기화
                    onSectionChange = { currentSection = it },
                    onRefresh = { isRefreshing = true; refreshTrigger++ },
                    onImageClick = { clickedIndex ->
                        startingIndex = clickedIndex
                        currentView = AppView.SwipeReview
                    }
                )
            }
            AppView.SwipeReview -> {
                val isVideoMode = currentSection == DashboardSection.Videos
                val activeList = if (isVideoMode) videoAssets else photoAssets

                PhotoReviewScreen(
                    activeMediaUris = activeList.map { it.uri },
                    isReviewingVideo = isVideoMode,
                    startingIndex = startingIndex,
                    trashUris = trashUris,
                    deleteLauncher = deleteLauncher,
                    onBackToDashboard = { finalIndex ->
                        // NEW: 뒤로가기를 누른 시점의 최종 사진 번호를 받아옵니다.
                        lastViewedIndex = finalIndex
                        currentView = AppView.Dashboard
                    }
                )
            }
        }
    }
}

@Composable
fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            PlayerView(context).apply {
                useController = false
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDashboardScreen(
    photoAssets: List<MediaAsset>,
    videoAssets: List<MediaAsset>,
    trashUris: MutableList<Uri>,
    deleteLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    isRefreshing: Boolean,
    currentSection: DashboardSection,
    gridState: LazyGridState, // 스크롤 컨트롤러
    lastViewedIndex: Int,     // 찾아갈 사진 번호
    onScrollComplete: () -> Unit,
    onSectionChange: (DashboardSection) -> Unit,
    onRefresh: () -> Unit,
    onImageClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val pullToRefreshState = rememberPullToRefreshState()

    val activeAssets = if (currentSection == DashboardSection.Photos) photoAssets else videoAssets

    // NEW: 날짜 헤더 때문에 뒤틀린 인덱스를 매핑(Mapping) 해주는 로직입니다.
    // 예를 들어, 5번째 사진이 날짜 헤더 2개 뒤에 있다면 실제 그리드에서는 7번째 칸에 있게 됩니다.
    val flatToGridIndex = remember(activeAssets) {
        val mapping = mutableMapOf<Int, Int>()
        var gridIdx = 0
        activeAssets.groupBy { it.dateString }.forEach { (_, assets) ->
            gridIdx++ // 날짜 헤더가 차지하는 1칸 추가
            assets.forEach { asset ->
                mapping[asset.flatIndex] = gridIdx
                gridIdx++
            }
        }
        mapping
    }

    // NEW: 리뷰 화면에서 번호를 가지고 돌아왔다면, 즉시 해당 위치로 스크롤합니다.
    LaunchedEffect(lastViewedIndex, activeAssets) {
        if (lastViewedIndex >= 0 && activeAssets.isNotEmpty()) {
            val safeIndex = lastViewedIndex.coerceAtMost(activeAssets.size - 1)
            val targetGridIndex = flatToGridIndex[safeIndex] ?: 0
            gridState.scrollToItem(targetGridIndex) // 목표 사진 위치로 점프!
            onScrollComplete() // 점프가 끝났으니 번호 초기화
        }
    }

    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) { onRefresh() }
    }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullToRefreshState.endRefresh()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (currentSection == DashboardSection.Photos) "Photos" else "Videos",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            if (trashUris.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, trashUris, true)
                            deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        }
                    },
                    containerColor = Color(0xFFFF4B4B),
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.Delete, contentDescription = "Trash") },
                    text = { Text("Empty (${trashUris.size})", fontWeight = FontWeight.Bold) },
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1E1E1E),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = currentSection == DashboardSection.Photos,
                    onClick = { onSectionChange(DashboardSection.Photos) },
                    icon = { Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("Photos", fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF4CAF50),
                        selectedTextColor = Color(0xFF4CAF50),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = currentSection == DashboardSection.Videos,
                    onClick = { onSectionChange(DashboardSection.Videos) },
                    icon = { Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text("Videos", fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF4CAF50),
                        selectedTextColor = Color(0xFF4CAF50),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
            if (activeAssets.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Nothing here! Pull down to refresh.", color = Color.Gray, fontSize = 16.sp)
                }
            } else {
                LazyVerticalGrid(
                    state = gridState, // NEW: 그리드에 상태를 적용해줍니다.
                    modifier = Modifier.fillMaxSize(),
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val groupedMedia = activeAssets.groupBy { it.dateString }

                    groupedMedia.forEach { (dateString, assetsForDate) ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = dateString,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp)
                            )
                        }
                        items(assetsForDate) { asset ->
                            Box(modifier = Modifier.aspectRatio(1f)) {
                                AsyncImage(
                                    model = asset.uri,
                                    contentDescription = "Media",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onImageClick(asset.flatIndex) },
                                    contentScale = ContentScale.Crop
                                )
                                if (currentSection == DashboardSection.Videos) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(24.dp)
                                            .shadow(2.dp, CircleShape)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            PullToRefreshContainer(
                state = pullToRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color(0xFF4CAF50)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoReviewScreen(
    activeMediaUris: List<Uri>,
    isReviewingVideo: Boolean,
    startingIndex: Int,
    trashUris: MutableList<Uri>,
    deleteLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    onBackToDashboard: (Int) -> Unit // NEW: 나갈 때 번호를 가져갈 수 있도록 변경
) {
    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(startingIndex) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    // NEW: 스마트폰 기본 뒤로가기 버튼 연동 (현재 번호 전달)
    BackHandler {
        onBackToDashboard(currentIndex)
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = { Text(if (isReviewingVideo) "Review Videos" else "Review Photos", fontSize = 20.sp, color = Color.White) },
                navigationIcon = {
                    // NEW: 앱 상단의 화살표 버튼 연동 (현재 번호 전달)
                    IconButton(onClick = { onBackToDashboard(currentIndex) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (trashUris.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, trashUris, true)
                                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Trash", tint = Color(0xFFFF4B4B))
                            Spacer(Modifier.width(4.dp))
                            Text("${trashUris.size}", color = Color(0xFFFF4B4B), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        if (activeMediaUris.isEmpty() || currentIndex >= activeMediaUris.size) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "All Caught Up!", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))
                // NEW: 스와이프를 끝까지 다 했을 때의 버튼 연동 (현재 번호 전달)
                Button(
                    onClick = { onBackToDashboard(currentIndex) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("Return to Grid", color = Color.White, fontSize = 16.sp)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .shadow(16.dp, RoundedCornerShape(32.dp))
                        .background(Color.Black, RoundedCornerShape(32.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = {
                                    if (offsetX > 300f) {
                                        currentIndex++
                                        offsetX = 0f
                                    } else if (offsetX < -300f) {
                                        trashUris.add(activeMediaUris[currentIndex])
                                        currentIndex++
                                        offsetX = 0f
                                    } else {
                                        offsetX = 0f
                                    }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isReviewingVideo) {
                        VideoPlayer(
                            uri = activeMediaUris[currentIndex],
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp))
                        )
                    } else {
                        AsyncImage(
                            model = activeMediaUris[currentIndex],
                            contentDescription = "Media to review",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            trashUris.add(activeMediaUris[currentIndex])
                            currentIndex++
                            offsetX = 0f
                        },
                        modifier = Modifier.size(64.dp).background(Color(0xFF1E1E1E), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Trash", tint = Color(0xFFFF4B4B), modifier = Modifier.size(32.dp))
                    }
                    IconButton(
                        onClick = {
                            if (currentIndex > 0) {
                                currentIndex--
                                trashUris.remove(activeMediaUris[currentIndex])
                                offsetX = 0f
                            }
                        },
                        modifier = Modifier.size(48.dp).background(Color.Transparent, CircleShape)
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo", tint = Color.Gray, modifier = Modifier.size(28.dp))
                    }
                    IconButton(
                        onClick = { currentIndex++; offsetX = 0f },
                        modifier = Modifier.size(64.dp).background(Color(0xFF1E1E1E), CircleShape)
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = "Keep", tint = Color(0xFF4CAF50), modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}
package com.simplemobiletools.gallery.activities

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.support.v7.widget.GridLayoutManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import com.bumptech.glide.Glide
import com.bumptech.glide.request.animation.GlideAnimation
import com.bumptech.glide.request.target.SimpleTarget
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.adapters.MediaAdapter
import com.simplemobiletools.gallery.asynctasks.GetMediaAsynctask
import com.simplemobiletools.gallery.dialogs.ChangeSortingDialog
import com.simplemobiletools.gallery.extensions.*
import com.simplemobiletools.gallery.helpers.*
import com.simplemobiletools.gallery.models.Medium
import com.simplemobiletools.gallery.views.MyScalableRecyclerView
import kotlinx.android.synthetic.main.activity_media.*
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.*

class MediaActivity : SimpleActivity(), MediaAdapter.MediaOperationsListener {
    private val TAG = MediaActivity::class.java.simpleName
    private val SAVE_MEDIA_CNT = 40

    private var mMedia = ArrayList<Medium>()

    private var mPath = ""
    private var mIsGetImageIntent = false
    private var mIsGetVideoIntent = false
    private var mIsGetAnyIntent = false
    private var mIsGettingMedia = false
    private var mShowAll = false
    private var mLoadedInitialPhotos = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        intent.apply {
            mIsGetImageIntent = getBooleanExtra(GET_IMAGE_INTENT, false)
            mIsGetVideoIntent = getBooleanExtra(GET_VIDEO_INTENT, false)
            mIsGetAnyIntent = getBooleanExtra(GET_ANY_INTENT, false)
        }

        media_holder.setOnRefreshListener({ getMedia() })
        mPath = intent.getStringExtra(DIRECTORY)
        mMedia = ArrayList<Medium>()
        mShowAll = config.showAll
        if (mShowAll)
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        tryloadGallery()
    }

    private fun tryloadGallery() {
        if (hasWriteStoragePermission()) {
            val dirName = getHumanizedFilename(mPath)
            title = if (mShowAll) resources.getString(R.string.all_folders) else dirName
            getMedia()
            handleZooming()
        } else {
            finish()
        }
    }

    private fun initializeGallery() {
        if (isDirEmpty())
            return

        val adapter = MediaAdapter(this, mMedia, this) {
            itemClicked(it.path)
        }

        val currAdapter = media_grid.adapter
        if (currAdapter != null) {
            (currAdapter as MediaAdapter).updateMedia(mMedia)
        } else {
            media_grid.adapter = adapter
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_media, menu)

        val isFolderHidden = config.getIsFolderHidden(mPath)
        menu.apply {
            findItem(R.id.hide_folder).isVisible = !isFolderHidden && !mShowAll
            findItem(R.id.unhide_folder).isVisible = isFolderHidden && !mShowAll

            findItem(R.id.folder_view).isVisible = mShowAll
            findItem(R.id.open_camera).isVisible = mShowAll
            findItem(R.id.settings).isVisible = mShowAll
            findItem(R.id.about).isVisible = mShowAll

            findItem(R.id.increase_column_count).isVisible = config.mediaColumnCnt < 10
            findItem(R.id.reduce_column_count).isVisible = config.mediaColumnCnt > 1
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> showSortingDialog()
            R.id.toggle_filename -> toggleFilenameVisibility()
            R.id.open_camera -> launchCamera()
            R.id.folder_view -> switchToFolderView()
            R.id.hide_folder -> hideFolder()
            R.id.unhide_folder -> unhideFolder()
            R.id.increase_column_count -> increaseColumnCount()
            R.id.reduce_column_count -> reduceColumnCount()
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleFilenameVisibility() {
        config.displayFileNames = !config.displayFileNames
        if (media_grid.adapter != null)
            (media_grid.adapter as MediaAdapter).updateDisplayFilenames(config.displayFileNames)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, false) {
            getMedia()
        }
    }

    private fun switchToFolderView() {
        config.showAll = false
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun hideFolder() {
        config.addHiddenFolder(mPath)

        if (!config.showHiddenFolders)
            finish()
        else
            invalidateOptionsMenu()
    }

    private fun unhideFolder() {
        config.removeHiddenFolder(mPath)
        invalidateOptionsMenu()
    }

    private fun deleteDirectoryIfEmpty() {
        val file = File(mPath)
        if (file.isDirectory && file.listFiles().isEmpty()) {
            file.delete()
        }
    }

    private fun getMedia() {
        if (mIsGettingMedia)
            return

        mIsGettingMedia = true
        val token = object : TypeToken<List<Medium>>() {}.type
        val media = Gson().fromJson<ArrayList<Medium>>(config.loadFolderMedia(mPath), token) ?: ArrayList<Medium>(1)
        if (media.size == 0) {
            media_holder.isRefreshing = true
        } else {
            if (!mLoadedInitialPhotos)
                gotMedia(media)
        }
        mLoadedInitialPhotos = true

        GetMediaAsynctask(applicationContext, mPath, mIsGetVideoIntent, mIsGetImageIntent, mShowAll) {
            gotMedia(it)
        }.execute()
    }

    private fun isDirEmpty(): Boolean {
        return if (mMedia.size <= 0) {
            deleteDirectoryIfEmpty()
            finish()
            true
        } else
            false
    }

    private fun handleZooming() {
        val layoutManager = media_grid.layoutManager as GridLayoutManager
        layoutManager.spanCount = config.mediaColumnCnt
        MyScalableRecyclerView.mListener = object : MyScalableRecyclerView.ZoomListener {
            override fun zoomIn() {
                if (layoutManager.spanCount > 1) {
                    reduceColumnCount()
                    MediaAdapter.actMode?.finish()
                }
            }

            override fun zoomOut() {
                if (layoutManager.spanCount < 10) {
                    increaseColumnCount()
                    MediaAdapter.actMode?.finish()
                }
            }
        }
    }

    private fun increaseColumnCount() {
        config.mediaColumnCnt = ++(media_grid.layoutManager as GridLayoutManager).spanCount
        invalidateOptionsMenu()
    }

    private fun reduceColumnCount() {
        config.mediaColumnCnt = --(media_grid.layoutManager as GridLayoutManager).spanCount
        invalidateOptionsMenu()
    }

    override fun deleteFiles(files: ArrayList<File>) {
        val needsPermissions = needsStupidWritePermissions(files[0].path)
        if (needsPermissions && isShowingPermDialog(files[0])) {
            return
        }

        Thread({
            var hadSuccess = false
            files.filter { it.exists() && it.isImageVideoGif() }
                    .forEach {
                        if (needsPermissions) {
                            val document = getFileDocument(it.absolutePath, config.treeUri)

                            // double check we have the uri to the proper file path, not some parent folder
                            val uri = URLDecoder.decode(document.uri.toString(), "UTF-8")
                            val filename = URLDecoder.decode(it.absolutePath.getFilenameFromPath(), "UTF-8")
                            if (uri.endsWith(filename) && !document.isDirectory) {
                                if (document.delete())
                                    hadSuccess = true
                            }
                        } else {
                            if (it.delete())
                                hadSuccess = true
                        }
                        deleteFromMediaStore(it)
                    }
            if (!hadSuccess)
                runOnUiThread {
                    toast(R.string.unknown_error_occurred)
                }
        }).start()

        if (mMedia.isEmpty()) {
            finish()
        }
    }

    private fun isSetWallpaperIntent() = intent.getBooleanExtra(SET_WALLPAPER_INTENT, false)

    fun itemClicked(path: String) {
        if (isSetWallpaperIntent()) {
            toast(R.string.setting_wallpaper)

            val wantedWidth = wallpaperDesiredMinimumWidth
            val wantedHeight = wallpaperDesiredMinimumHeight
            val ratio = wantedWidth.toFloat() / wantedHeight
            Glide.with(this)
                    .load(File(path))
                    .asBitmap()
                    .override((wantedWidth * ratio).toInt(), wantedHeight)
                    .fitCenter()
                    .into(object : SimpleTarget<Bitmap>() {
                        override fun onResourceReady(bitmap: Bitmap?, glideAnimation: GlideAnimation<in Bitmap>?) {
                            try {
                                WallpaperManager.getInstance(applicationContext).setBitmap(bitmap)
                                setResult(Activity.RESULT_OK)
                            } catch (e: IOException) {
                                Log.e(TAG, "item click $e")
                            }

                            finish()
                        }
                    })
        } else if (mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent) {
            Intent().apply {
                data = Uri.parse(path)
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else {
            Intent(this, ViewPagerActivity::class.java).apply {
                putExtra(MEDIUM, path)
                putExtra(SHOW_ALL, mShowAll)
                startActivity(this)
            }
        }
    }

    fun gotMedia(media: ArrayList<Medium>) {
        mIsGettingMedia = false
        media_holder.isRefreshing = false

        if (media.hashCode() == mMedia.hashCode())
            return

        mMedia = media
        initializeGallery()
        storeFolder()
    }

    private fun storeFolder() {
        val subList = mMedia.subList(0, Math.min(SAVE_MEDIA_CNT, mMedia.size))
        val json = Gson().toJson(subList)
        config.saveFolderMedia(mPath, json)
    }

    override fun refreshItems() {
        getMedia()
    }
}

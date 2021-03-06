/*
 * This file is part of Material Audiobook Player.
 *
 * Material Audiobook Player is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * Material Audiobook Player is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Material Audiobook Player. If not, see <http://www.gnu.org/licenses/>.
 * /licenses/>.
 */

package de.ph1b.audiobook.features.folder_chooser

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.widget.Toolbar
import android.view.MenuItem
import android.view.View
import android.widget.*
import butterknife.bindView
import com.jakewharton.rxbinding.widget.RxAdapterView
import com.jakewharton.rxbinding.widget.itemClicks
import de.ph1b.audiobook.R
import de.ph1b.audiobook.injection.App
import de.ph1b.audiobook.misc.MultiLineSpinnerAdapter
import de.ph1b.audiobook.misc.PermissionHelper
import de.ph1b.audiobook.mvp.RxBaseActivity
import e
import i
import java.io.File

/**
 * Activity for choosing an audiobook folder. If there are multiple SD-Cards, the Activity unifies
 * them to a fake-folder structure. We must make sure that this is not choosable. When there are no
 * multiple sd-cards, we will directly show the content of the 1 SD Card.
 *
 *
 * Use [newInstanceIntent] to get a new intent with the necessary
 * values.

 * @author Paul Woitaschek
 */
class FolderChooserActivity : RxBaseActivity<FolderChooserView, FolderChooserPresenter>(), FolderChooserView, HideFolderDialog.OnChosenListener {

    override fun newPresenter() = FolderChooserPresenter()

    override fun provideView() = this

    override fun showSubFolderWarning(first: String, second: String) {
        val message = "${getString(R.string.adding_failed_subfolder)}\n$first\n$second"
        Toast.makeText(this, message, Toast.LENGTH_LONG)
                .show()
    }

    init {
        App.component().inject(this)
    }

    private val upButton: ImageButton by bindView(R.id.twoline_image1)
    private val currentFolderName: TextView by bindView(R.id.twoline_text2)
    private val chooseButton: Button by bindView(R.id.choose)
    private val toolbar: Toolbar by bindView(R.id.toolbar)
    private val listView: ListView by bindView(R.id.listView)
    private val chosenFolderDescription: TextView by bindView(R.id.twoline_text1)
    private val spinner: Spinner by bindView(R.id.toolSpinner)
    private val spinnerGroup: View by bindView(R.id.spinnerGroup)
    private val abortButton: View  by bindView(R.id.abort)

    private lateinit var adapter: FolderChooserAdapter
    private lateinit var spinnerAdapter: MultiLineSpinnerAdapter<File>

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun askForReadExternalStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_RESULT_READ_EXT_STORAGE)
    }

    override fun askAddNoMediaFile(folderToHide: File) {
        val hideFolderDialog = HideFolderDialog.newInstance(folderToHide)
        hideFolderDialog.show(supportFragmentManager, HideFolderDialog.TAG)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        i { "onRequestPermissionsResult called" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val permissionGrantingWorked = PermissionHelper.permissionGrantingWorked(requestCode,
                    PERMISSION_RESULT_READ_EXT_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE,
                    permissions, grantResults)
            i { "permissionGrantingWorked=$permissionGrantingWorked" }
            if (permissionGrantingWorked) {
                presenter()!!.gotPermission()
            } else {
                PermissionHelper.handleExtStorageRescan(this, PERMISSION_RESULT_READ_EXT_STORAGE)
                e { "could not get permission" }
            }
        }
    }

    override fun getMode() = OperationMode.valueOf(intent.getStringExtra(NI_OPERATION_MODE))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val hasExternalStoragePermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            i { "hasExternalStoragePermission=$hasExternalStoragePermission" }
            if (!hasExternalStoragePermission) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    PermissionHelper.handleExtStorageRescan(this, PERMISSION_RESULT_READ_EXT_STORAGE)
                } else {
                    askForReadExternalStoragePermission()
                }
            }
        }

        // find views
        setContentView(R.layout.activity_folder_chooser)

        // toolbar
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayShowTitleEnabled(false)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // listeners
        chooseButton.setOnClickListener { presenter()!!.chooseClicked() }
        abortButton.setOnClickListener { finish() }
        upButton.setOnClickListener { onBackPressed() }

        // text
        chosenFolderDescription.setText(R.string.chosen_folder_description)

        //recycler
        adapter = FolderChooserAdapter(this, getMode())
        listView.adapter = adapter
        listView.itemClicks()
                .subscribe {
                    val selectedFile = adapter.getItem(it)
                    presenter()!!.fileSelected(selectedFile)
                }

        // spinner
        spinnerAdapter = MultiLineSpinnerAdapter(spinner, this, Color.WHITE)
        spinner.adapter = spinnerAdapter
        RxAdapterView.itemSelections(spinner)
                .filter { it != AdapterView.INVALID_POSITION } // filter invalid entries
                .skip(1) // skip the first that passed as its no real user input
                .subscribe {
                    i { "spinner selected with position $it and adapter.count ${spinnerAdapter.count}" }
                    val item = spinnerAdapter.getItem(it)
                    presenter()!!.fileSelected(item.data)
                }
    }

    override fun onBackPressed() {
        if (!presenter()!!.backConsumed()) {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item?.itemId == android.R.id.home) {
            finish()
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    override fun setCurrentFolderText(text: String) {
        currentFolderName.text = text
    }

    override fun showNewData(newData: List<File>) {
        adapter.newData(newData)
    }

    override fun setChooseButtonEnabled(chooseEnabled: Boolean) {
        chooseButton.isEnabled = chooseEnabled
    }

    override fun newRootFolders(newFolders: List<File>) {
        i { "newRootFolders called with $newFolders" }
        spinnerGroup.visibility = if (newFolders.size <= 1) View.INVISIBLE else View.VISIBLE

        val newData = newFolders
                .map {
                    val name = if (it.absolutePath == FolderChooserPresenter.MARSHMALLOW_SD_FALLBACK) {
                        getString(R.string.storage_all)
                    } else {
                        it.name
                    }
                    MultiLineSpinnerAdapter.Data(it, name)
                }
        spinnerAdapter.setData(newData)
    }


    /**
     * Sets the choose button enabled or disabled, depending on where we are in the hierarchy
     */
    override fun setUpButtonEnabled(upEnabled: Boolean) {
        upButton.isEnabled = upEnabled
        val upIcon = if (upEnabled) ContextCompat.getDrawable(this, R.drawable.ic_arrow_upward) else null
        upButton.setImageDrawable(upIcon)
    }

    override fun finishWithResult() {
        i { "finishWithResult" }
        setResult(Activity.RESULT_OK, Intent())
        finish()
    }

    override fun onChosen() {
        presenter()!!.hideFolderSelectionMade()
    }

    enum class OperationMode {
        COLLECTION_BOOK,
        SINGLE_BOOK
    }

    companion object {

        private val NI_OPERATION_MODE = "niOperationMode"
        private val PERMISSION_RESULT_READ_EXT_STORAGE = 1

        /**
         * Generates a new intent with the necessary extras

         * @param c             The context
         * *
         * @param operationMode The operation mode for the activity
         * *
         * @return The new intent
         */
        fun newInstanceIntent(c: Context, operationMode: OperationMode): Intent {
            val intent = Intent(c, FolderChooserActivity::class.java)
            intent.putExtra(NI_OPERATION_MODE, operationMode.name)
            return intent
        }
    }
}
/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.kunzisoft.keepass.activities

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.snackbar.Snackbar
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.*
import com.kunzisoft.keepass.activities.dialogs.FileTooBigDialogFragment.Companion.MAX_WARNING_BINARY_FILE
import com.kunzisoft.keepass.activities.fragments.EntryEditFragment
import com.kunzisoft.keepass.activities.helpers.EntrySelectionHelper
import com.kunzisoft.keepass.activities.helpers.ExternalFileHelper
import com.kunzisoft.keepass.activities.legacy.DatabaseLockActivity
import com.kunzisoft.keepass.adapters.TemplatesSelectorAdapter
import com.kunzisoft.keepass.app.database.IOActionTask
import com.kunzisoft.keepass.autofill.AutofillComponent
import com.kunzisoft.keepass.autofill.AutofillHelper
import com.kunzisoft.keepass.database.element.*
import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.node.Node
import com.kunzisoft.keepass.database.element.node.NodeId
import com.kunzisoft.keepass.database.element.template.*
import com.kunzisoft.keepass.education.EntryEditActivityEducation
import com.kunzisoft.keepass.model.*
import com.kunzisoft.keepass.otp.OtpElement
import com.kunzisoft.keepass.services.AttachmentFileNotificationService
import com.kunzisoft.keepass.services.ClipboardEntryNotificationService
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_ENTRY_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_ENTRY_TASK
import com.kunzisoft.keepass.services.KeyboardEntryNotificationService
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.tasks.AttachmentFileBinderManager
import com.kunzisoft.keepass.timeout.TimeoutHelper
import com.kunzisoft.keepass.utils.UriUtil
import com.kunzisoft.keepass.view.*
import com.kunzisoft.keepass.viewmodels.EntryEditViewModel
import org.joda.time.DateTime
import java.util.*
import kotlin.collections.ArrayList

class EntryEditActivity : DatabaseLockActivity(),
        EntryCustomFieldDialogFragment.EntryCustomFieldListener,
        GeneratePasswordDialogFragment.GeneratePasswordListener,
        SetOTPDialogFragment.CreateOtpListener,
        DatePickerDialog.OnDateSetListener,
        TimePickerDialog.OnTimeSetListener,
        FileTooBigDialogFragment.ActionChooseListener,
        ReplaceFileDialogFragment.ActionChooseListener {

    // Views
    private var coordinatorLayout: CoordinatorLayout? = null
    private var scrollView: NestedScrollView? = null
    private var templateSelectorSpinner: Spinner? = null
    private var entryEditAddToolBar: ToolbarAction? = null
    private var validateButton: View? = null
    private var lockView: View? = null
    private var loadingView: ProgressBar? = null

    private var mEntryId: NodeId<UUID>? = null
    private var mParentId: NodeId<*>? = null
    private var mRegisterInfo: RegisterInfo? = null
    private var mSearchInfo: SearchInfo? = null

    private val mEntryEditViewModel: EntryEditViewModel by viewModels()
    private var mParent: Group? = null
    private var mEntry: Entry? = null
    private var mIsTemplate: Boolean = false

    private var mAllowCustomFields = false
    private var mAllowOTP = false

    // To manage attachments
    private var mExternalFileHelper: ExternalFileHelper? = null
    private var mAttachmentFileBinderManager: AttachmentFileBinderManager? = null
    // Education
    private var entryEditActivityEducation: EntryEditActivityEducation? = null

    // To ask data lost only one time
    private var backPressedAlreadyApproved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry_edit)

        // Bottom Bar
        entryEditAddToolBar = findViewById(R.id.entry_edit_bottom_bar)
        setSupportActionBar(entryEditAddToolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        coordinatorLayout = findViewById(R.id.entry_edit_coordinator_layout)
        scrollView = findViewById(R.id.entry_edit_scroll)
        scrollView?.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        templateSelectorSpinner = findViewById(R.id.entry_edit_template_selector)
        lockView = findViewById(R.id.lock_button)
        validateButton = findViewById(R.id.entry_edit_validate)
        loadingView = findViewById(R.id.loading)

        stopService(Intent(this, ClipboardEntryNotificationService::class.java))
        stopService(Intent(this, KeyboardEntryNotificationService::class.java))

        mRegisterInfo = EntrySelectionHelper.retrieveRegisterInfoFromIntent(intent)
        mSearchInfo = EntrySelectionHelper.retrieveSearchInfoFromIntent(intent)

        // Entry is retrieve, it's an entry to update
        intent.getParcelableExtra<NodeId<UUID>>(KEY_ENTRY)?.let { entryToUpdate ->
            intent.removeExtra(KEY_ENTRY)
            mEntryId = entryToUpdate
        }

        // Parent is retrieve, it's a new entry to create
        intent.getParcelableExtra<NodeId<*>>(KEY_PARENT)?.let { parent ->
            intent.removeExtra(KEY_PARENT)
            mParentId = parent
        }

        retrieveEntry(mDatabase)

        // To retrieve attachment
        mExternalFileHelper = ExternalFileHelper(this)
        mAttachmentFileBinderManager = AttachmentFileBinderManager(this)
        // Verify the education views
        entryEditActivityEducation = EntryEditActivityEducation(this)

        // Lock button
        lockView?.setOnClickListener { lockAndExit() }
        // Save button
        validateButton?.setOnClickListener { saveEntry() }

        mEntryEditViewModel.entryInfo.observe(this) {
            loadingView?.hideByFading()
        }

        // View model listeners
        mEntryEditViewModel.requestIconSelection.observe(this) { iconImage ->
            IconPickerActivity.launch(this@EntryEditActivity, iconImage)
        }

        mEntryEditViewModel.requestDateTimeSelection.observe(this) { dateInstant ->
            if (dateInstant.type == DateInstant.Type.TIME) {
                // Launch the time picker
                val dateTime = DateTime(dateInstant.date)
                TimePickerFragment.getInstance(dateTime.hourOfDay, dateTime.minuteOfHour)
                    .show(supportFragmentManager, "TimePickerFragment")
            } else {
                // Launch the date picker
                val dateTime = DateTime(dateInstant.date)
                DatePickerFragment.getInstance(dateTime.year, dateTime.monthOfYear - 1, dateTime.dayOfMonth)
                    .show(supportFragmentManager, "DatePickerFragment")
            }
        }

        mEntryEditViewModel.requestPasswordSelection.observe(this) { passwordField ->
            GeneratePasswordDialogFragment
                    .getInstance(passwordField)
                    .show(supportFragmentManager, "PasswordGeneratorFragment")
        }

        mEntryEditViewModel.requestCustomFieldEdition.observe(this) { field ->
            editCustomField(field)
        }

        mEntryEditViewModel.onCustomFieldError.observe(this) {
            coordinatorLayout?.let {
                Snackbar.make(it, R.string.error_field_name_already_exists, Snackbar.LENGTH_LONG)
                        .asError()
                        .show()
            }
        }

        mEntryEditViewModel.onStartUploadAttachment.observe(this) {
            // Start uploading in service
            mAttachmentFileBinderManager?.startUploadAttachment(it.attachmentToUploadUri, it.attachment)
        }

        mEntryEditViewModel.onAttachmentAction.observe(this) { attachmentState ->
            when (attachmentState?.downloadState) {
                AttachmentState.ERROR -> {
                    coordinatorLayout?.let {
                        Snackbar.make(it, R.string.error_file_not_create, Snackbar.LENGTH_LONG).asError().show()
                    }
                }
                else -> {}
            }
        }

        mEntryEditViewModel.onBinaryPreviewLoaded.observe(this) {
            // Scroll to the attachment position
            when (it.entryAttachmentState.downloadState) {
                AttachmentState.START,
                AttachmentState.COMPLETE -> {
                    scrollView?.smoothScrollTo(0, it.viewPosition.toInt())
                }
                else -> {}
            }
        }

        mEntryEditViewModel.attachmentDeleted.observe(this) {
            mAttachmentFileBinderManager?.removeBinaryAttachment(it)
        }

        // Change template dynamically
        mEntryEditViewModel.templates.observe(this) { templatesLoaded ->
            val templates = templatesLoaded.templates
            val defaultTemplate = templatesLoaded.defaultTemplate
            templateSelectorSpinner?.apply {
                // Build template selector
                if (templates.isNotEmpty()) {
                    adapter = TemplatesSelectorAdapter(this@EntryEditActivity, mIconDrawableFactory, templates)
                    setSelection(templates.indexOf(defaultTemplate))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            mEntryEditViewModel.changeTemplate(templates[position])
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                } else {
                    visibility = View.GONE
                }
            }
        }

        // Build new entry from the entry info retrieved
        mEntryEditViewModel.onEntrySaved.observe(this) { entrySave ->
            // Open a progress dialog and save entry
            entrySave.parent?.let { parent ->
                createEntry(entrySave.newEntry, parent)
            } ?: run {
                updateEntry(entrySave.oldEntry, entrySave.newEntry)
            }
        }
    }

    override fun viewToInvalidateTimeout(): View? {
        return coordinatorLayout
    }

    override fun finishActivityIfReloadRequested(): Boolean {
        return true
    }

    override fun onDatabaseRetrieved(database: Database?) {
        super.onDatabaseRetrieved(database)
        mAllowCustomFields = database?.allowEntryCustomFields() == true
        mAllowOTP = database?.allowOTP == true
        retrieveEntry(database)
    }

    private fun retrieveEntry(database: Database?) {
        database?.let {
            mEntryId?.let {
                IOActionTask(
                    {
                        // Create an Entry copy to modify from the database entry
                        mEntry = database.getEntryById(it)
                        // Retrieve the parent
                        mEntry?.let { entry ->
                            // If no parent, add root group as parent
                            if (entry.parent == null) {
                                entry.parent = database.rootGroup
                            }
                        }
                        // Define if current entry is a template (in direct template group)
                        mIsTemplate = database.entryIsTemplate(mEntry)
                    },
                    {
                        mEntryEditViewModel.loadTemplateEntry(
                            database,
                            mEntry,
                            mIsTemplate,
                            mRegisterInfo,
                            mSearchInfo
                        )
                    }
                ).execute()
                mEntryId = null
            }

            mParentId?.let {
                IOActionTask(
                    {
                        mParent = database.getGroupById(it)
                        mParent?.let { parentGroup ->
                            mEntry = database.createEntry()?.apply {
                                // Add the default icon from parent if not a folder
                                val parentIcon = parentGroup.icon
                                // Set default icon
                                if (parentIcon.custom.isUnknown
                                    && parentIcon.standard.id != IconImageStandard.FOLDER_ID
                                ) {
                                    icon = IconImage(parentIcon.standard)
                                }
                                if (!parentIcon.custom.isUnknown) {
                                    icon = IconImage(parentIcon.custom)
                                }
                                // Set default username
                                username = database.defaultUsername
                                // Warning only the entry recognize is parent, parent don't yet recognize the new entry
                                // Useful to recognize child state (ie: entry is a template)
                                parent = parentGroup
                            }
                        }
                        mIsTemplate = database.entryIsTemplate(mEntry)
                    },
                    {
                        mEntryEditViewModel.loadTemplateEntry(
                            database,
                            mEntry,
                            mIsTemplate,
                            mRegisterInfo,
                            mSearchInfo
                        )
                    }
                ).execute()
                mParentId = null
            }
        }
    }

    override fun onDatabaseActionFinished(
        database: Database,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        super.onDatabaseActionFinished(database, actionTask, result)
        when (actionTask) {
            ACTION_DATABASE_CREATE_ENTRY_TASK,
            ACTION_DATABASE_UPDATE_ENTRY_TASK -> {
                try {
                    if (result.isSuccess) {
                        var newNodes: List<Node> = ArrayList()
                        result.data?.getBundle(DatabaseTaskNotificationService.NEW_NODES_KEY)?.let { newNodesBundle ->
                            newNodes = DatabaseTaskNotificationService.getListNodesFromBundle(database, newNodesBundle)
                        }
                        if (newNodes.size == 1) {
                            (newNodes[0] as? Entry?)?.let { entry ->
                                EntrySelectionHelper.doSpecialAction(intent,
                                    {
                                        // Finish naturally
                                        finishForEntryResult(actionTask, entry)
                                    },
                                    {
                                        // Nothing when search retrieved
                                    },
                                    {
                                        entryValidatedForSave(actionTask, entry)
                                    },
                                    {
                                        entryValidatedForKeyboardSelection(database, actionTask, entry)
                                    },
                                    { _, _ ->
                                        entryValidatedForAutofillSelection(database, entry)
                                    },
                                    {
                                        entryValidatedForAutofillRegistration(actionTask, entry)
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to retrieve entry after database action", e)
                }
            }
        }
        coordinatorLayout?.showActionErrorIfNeeded(result)
    }

    private fun entryValidatedForSave(actionTask: String, entry: Entry) {
        onValidateSpecialMode()
        finishForEntryResult(actionTask, entry)
    }

    private fun entryValidatedForKeyboardSelection(database: Database, actionTask: String, entry: Entry) {
        // Populate Magikeyboard with entry
        populateKeyboardAndMoveAppToBackground(this,
                entry.getEntryInfo(database),
                intent)
        onValidateSpecialMode()
        // Don't keep activity history for entry edition
        finishForEntryResult(actionTask, entry)
    }

    private fun entryValidatedForAutofillSelection(database: Database, entry: Entry) {
        // Build Autofill response with the entry selected
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillHelper.buildResponseAndSetResult(this@EntryEditActivity,
                    database,
                    entry.getEntryInfo(database))
        }
        onValidateSpecialMode()
    }

    private fun entryValidatedForAutofillRegistration(actionTask: String, entry: Entry) {
        onValidateSpecialMode()
        finishForEntryResult(actionTask, entry)
    }

    override fun onResume() {
        super.onResume()

        lockView?.visibility = if (PreferencesUtil.showLockDatabaseButton(this)) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Padding if lock button visible
        entryEditAddToolBar?.updateLockPaddingLeft()

        mAttachmentFileBinderManager?.apply {
            registerProgressTask()
            onActionTaskListener = object : AttachmentFileNotificationService.ActionTaskListener {
                override fun onAttachmentAction(fileUri: Uri, entryAttachmentState: EntryAttachmentState) {
                    mEntryEditViewModel.onAttachmentAction(entryAttachmentState)
                }
            }
        }
    }

    override fun onPause() {
        mAttachmentFileBinderManager?.unregisterProgressTask()

        super.onPause()
    }

    /**
     * Add a new customized field
     */
    private fun addNewCustomField() {
        EntryCustomFieldDialogFragment.getInstance().show(supportFragmentManager, "customFieldDialog")
    }

    private fun editCustomField(field: Field) {
        EntryCustomFieldDialogFragment.getInstance(field).show(supportFragmentManager, "customFieldDialog")
    }

    override fun onNewCustomFieldApproved(newField: Field) {
        mEntryEditViewModel.addCustomField(newField)
    }

    override fun onEditCustomFieldApproved(oldField: Field, newField: Field) {
        mEntryEditViewModel.editCustomField(oldField, newField)
    }

    override fun onDeleteCustomFieldApproved(oldField: Field) {
        mEntryEditViewModel.removeCustomField(oldField)
    }

    /**
     * Add a new attachment
     */
    private fun addNewAttachment() {
        mExternalFileHelper?.openDocument()
    }

    override fun onValidateUploadFileTooBig(attachmentToUploadUri: Uri?, fileName: String?) {
        if (attachmentToUploadUri != null && fileName != null) {
            mEntryEditViewModel.buildNewAttachment(attachmentToUploadUri, fileName)
        }
    }

    override fun onValidateReplaceFile(attachmentToUploadUri: Uri?, attachment: Attachment?) {
        if (attachmentToUploadUri != null && attachment != null) {
            mEntryEditViewModel.startUploadAttachment(attachmentToUploadUri, attachment)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        IconPickerActivity.onActivityResult(requestCode, resultCode, data) { icon ->
            mEntryEditViewModel.selectIcon(icon)
        }

        mExternalFileHelper?.onOpenDocumentResult(requestCode, resultCode, data) { uri ->
            uri?.let { attachmentToUploadUri ->
                UriUtil.getFileData(this, attachmentToUploadUri)?.also { documentFile ->
                    documentFile.name?.let { fileName ->
                        if (documentFile.length() > MAX_WARNING_BINARY_FILE) {
                            FileTooBigDialogFragment.build(attachmentToUploadUri, fileName)
                                    .show(supportFragmentManager, "fileTooBigFragment")
                        } else {
                            mEntryEditViewModel.buildNewAttachment(attachmentToUploadUri, fileName)
                        }
                    }
                }
            }
        }
    }

    /**
     * Set up OTP (HOTP or TOTP) and add it as extra field
     */
    private fun setupOtp() {
        mEntryEditViewModel.setupOtp()
    }

    override fun onOtpCreated(otpElement: OtpElement) {
        mEntryEditViewModel.createOtp(otpElement)
    }

    /**
     * Saves the new entry or update an existing entry in the database
     */
    private fun saveEntry() {
        mAttachmentFileBinderManager?.stopUploadAllAttachments()
        mEntryEditViewModel.requestEntryInfoUpdate(mDatabase, mEntry, mParent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.entry_edit, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {

        menu?.findItem(R.id.menu_add_field)?.apply {
            isEnabled = mAllowCustomFields
            isVisible = isEnabled
        }

        menu?.findItem(R.id.menu_add_attachment)?.apply {
            // Attachment not compatible below KitKat
            isEnabled = !mIsTemplate
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
            isVisible = isEnabled
        }

        menu?.findItem(R.id.menu_add_otp)?.apply {
            // OTP not compatible below KitKat
            isEnabled = mAllowOTP
                    && !mIsTemplate
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
            isVisible = isEnabled
        }

        entryEditActivityEducation?.let {
            Handler(Looper.getMainLooper()).post { performedNextEducation(it) }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    fun performedNextEducation(entryEditActivityEducation: EntryEditActivityEducation) {

        val entryEditFragment = supportFragmentManager.findFragmentById(R.id.entry_edit_content)
                as? EntryEditFragment?
        val generatePasswordView = entryEditFragment?.getActionImageView()
        val generatePasswordEductionPerformed = generatePasswordView != null
                && entryEditActivityEducation.checkAndPerformedGeneratePasswordEducation(
            generatePasswordView,
            {
                entryEditFragment.launchGeneratePasswordEductionAction()
            },
            {
                performedNextEducation(entryEditActivityEducation)
            }
        )

        if (!generatePasswordEductionPerformed) {
            val addNewFieldView: View? = entryEditAddToolBar?.findViewById(R.id.menu_add_field)
            val addNewFieldEducationPerformed = mAllowCustomFields
                    && addNewFieldView != null
                    && addNewFieldView.isVisible
                    && entryEditActivityEducation.checkAndPerformedEntryNewFieldEducation(
                    addNewFieldView,
                    {
                        addNewCustomField()
                    },
                    {
                        performedNextEducation(entryEditActivityEducation)
                    }
            )
            if (!addNewFieldEducationPerformed) {
                val attachmentView: View? = entryEditAddToolBar?.findViewById(R.id.menu_add_attachment)
                val addAttachmentEducationPerformed = attachmentView != null
                        && attachmentView.isVisible
                        && entryEditActivityEducation.checkAndPerformedAttachmentEducation(
                        attachmentView,
                        {
                            mExternalFileHelper?.openDocument()
                        },
                        {
                            performedNextEducation(entryEditActivityEducation)
                        }
                )
                if (!addAttachmentEducationPerformed) {
                    val setupOtpView: View? = entryEditAddToolBar?.findViewById(R.id.menu_add_otp)
                    setupOtpView != null
                            && setupOtpView.isVisible
                            && entryEditActivityEducation.checkAndPerformedSetUpOTPEducation(
                            setupOtpView,
                            {
                                setupOtp()
                            }
                    )
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_field -> {
                addNewCustomField()
                return true
            }
            R.id.menu_add_attachment -> {
                addNewAttachment()
                return true
            }
            R.id.menu_add_otp -> {
                setupOtp()
                return true
            }
            android.R.id.home -> {
                onBackPressed()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDateSet(datePicker: DatePicker?, year: Int, month: Int, day: Int) {
        // To fix android 4.4 issue
        // https://stackoverflow.com/questions/12436073/datepicker-ondatechangedlistener-called-twice
        if (datePicker?.isShown == true) {
            mEntryEditViewModel.selectDate(year, month, day)
        }
    }

    override fun onTimeSet(timePicker: TimePicker?, hours: Int, minutes: Int) {
        mEntryEditViewModel.selectTime(hours, minutes)
    }

    override fun acceptPassword(passwordField: Field) {
        mEntryEditViewModel.selectPassword(passwordField)
        entryEditActivityEducation?.let {
            Handler(Looper.getMainLooper()).post { performedNextEducation(it) }
        }
    }

    override fun cancelPassword(passwordField: Field) {
        // Do nothing here
    }

    override fun onBackPressed() {
        onApprovedBackPressed {
            super@EntryEditActivity.onBackPressed()
        }
    }

    override fun onCancelSpecialMode() {
        onApprovedBackPressed {
            super@EntryEditActivity.onCancelSpecialMode()
            finish()
        }
    }

    private fun onApprovedBackPressed(approved: () -> Unit) {
        if (!backPressedAlreadyApproved) {
            AlertDialog.Builder(this)
                    .setMessage(R.string.discard_changes)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.discard) { _, _ ->
                        mAttachmentFileBinderManager?.stopUploadAllAttachments()
                        backPressedAlreadyApproved = true
                        approved.invoke()
                    }.create().show()
        } else {
            approved.invoke()
        }
    }

    private fun finishForEntryResult(actionTask: String, entry: Entry) {
        // Assign entry callback as a result
        try {
            val bundle = Bundle()
            val intentEntry = Intent()
            bundle.putParcelable(ADD_OR_UPDATE_ENTRY_KEY, entry.nodeId)
            intentEntry.putExtras(bundle)
            when (actionTask) {
                ACTION_DATABASE_CREATE_ENTRY_TASK -> {
                    setResult(ADD_ENTRY_RESULT_CODE, intentEntry)
                }
                ACTION_DATABASE_UPDATE_ENTRY_TASK -> {
                    setResult(UPDATE_ENTRY_RESULT_CODE, intentEntry)
                }
            }
            super.finish()
        } catch (e: Exception) {
            // Exception when parcelable can't be done
            Log.e(TAG, "Cant add entry as result", e)
        }
    }

    companion object {

        private val TAG = EntryEditActivity::class.java.name

        // Keys for current Activity
        const val KEY_ENTRY = "entry"
        const val KEY_PARENT = "parent"

        // Keys for callback
        const val ADD_ENTRY_RESULT_CODE = 31
        const val UPDATE_ENTRY_RESULT_CODE = 32
        const val ADD_OR_UPDATE_ENTRY_REQUEST_CODE = 7129
        const val ADD_OR_UPDATE_ENTRY_KEY = "ADD_OR_UPDATE_ENTRY_KEY"

        // TODO Only not readonly

        /**
         * Launch EntryEditActivity to update an existing entry
         *
         * @param activity from activity
         * @param entryId Entry to update
         */
        fun launchToUpdate(activity: Activity,
                           entryId: NodeId<UUID>) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(activity)) {
                val intent = Intent(activity, EntryEditActivity::class.java)
                intent.putExtra(KEY_ENTRY, entryId)
                activity.startActivityForResult(intent, ADD_OR_UPDATE_ENTRY_REQUEST_CODE)
            }
        }

        /**
         * Launch EntryEditActivity to add a new entry
         *
         * @param activity from activity
         * @param groupId Group who will contains new entry
         */
        fun launchToCreate(activity: Activity,
                           groupId: NodeId<*>) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(activity)) {
                val intent = Intent(activity, EntryEditActivity::class.java)
                intent.putExtra(KEY_PARENT, groupId)
                activity.startActivityForResult(intent, ADD_OR_UPDATE_ENTRY_REQUEST_CODE)
            }
        }

        fun launchToUpdateForSave(context: Context,
                                  entryId: NodeId<UUID>,
                                  searchInfo: SearchInfo) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(context)) {
                val intent = Intent(context, EntryEditActivity::class.java)
                intent.putExtra(KEY_ENTRY, entryId)
                EntrySelectionHelper.startActivityForSaveModeResult(context,
                        intent,
                        searchInfo)
            }
        }

        fun launchToCreateForSave(context: Context,
                                  groupId: NodeId<*>,
                                  searchInfo: SearchInfo) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(context)) {
                val intent = Intent(context, EntryEditActivity::class.java)
                intent.putExtra(KEY_PARENT, groupId)
                EntrySelectionHelper.startActivityForSaveModeResult(context,
                        intent,
                        searchInfo)
            }
        }

        /**
         * Launch EntryEditActivity to add a new entry in keyboard selection
         */
        fun launchForKeyboardSelectionResult(context: Context,
                                             groupId: NodeId<*>,
                                             searchInfo: SearchInfo? = null) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(context)) {
                val intent = Intent(context, EntryEditActivity::class.java)
                intent.putExtra(KEY_PARENT, groupId)
                EntrySelectionHelper.startActivityForKeyboardSelectionModeResult(context,
                        intent,
                        searchInfo)
            }
        }

        /**
         * Launch EntryEditActivity to add a new entry in autofill selection
         */
        @RequiresApi(api = Build.VERSION_CODES.O)
        fun launchForAutofillResult(activity: Activity,
                                    autofillComponent: AutofillComponent,
                                    groupId: NodeId<*>,
                                    searchInfo: SearchInfo? = null) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(activity)) {
                val intent = Intent(activity, EntryEditActivity::class.java)
                intent.putExtra(KEY_PARENT, groupId)
                AutofillHelper.startActivityForAutofillResult(activity,
                        intent,
                        autofillComponent,
                        searchInfo)
            }
        }

        /**
         * Launch EntryEditActivity to register an updated entry (from autofill)
         */
        fun launchToUpdateForRegistration(context: Context,
                                          entryId: NodeId<UUID>,
                                          registerInfo: RegisterInfo? = null) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(context)) {
                val intent = Intent(context, EntryEditActivity::class.java)
                intent.putExtra(KEY_ENTRY, entryId)
                EntrySelectionHelper.startActivityForRegistrationModeResult(context,
                        intent,
                        registerInfo)
            }
        }

        /**
         * Launch EntryEditActivity to register a new entry (from autofill)
         */
        fun launchToCreateForRegistration(context: Context,
                                          groupId: NodeId<*>,
                                          registerInfo: RegisterInfo? = null) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(context)) {
                val intent = Intent(context, EntryEditActivity::class.java)
                intent.putExtra(KEY_PARENT, groupId)
                EntrySelectionHelper.startActivityForRegistrationModeResult(context,
                        intent,
                        registerInfo)
            }
        }
    }
}

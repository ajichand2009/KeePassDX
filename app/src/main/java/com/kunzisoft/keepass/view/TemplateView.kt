package com.kunzisoft.keepass.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.view.isVisible
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.Field
import com.kunzisoft.keepass.database.element.security.ProtectedString
import com.kunzisoft.keepass.database.element.template.TemplateAttribute
import com.kunzisoft.keepass.database.element.template.TemplateAttributeType
import com.kunzisoft.keepass.database.element.template.TemplateField
import com.kunzisoft.keepass.model.OtpModel
import com.kunzisoft.keepass.otp.OtpElement
import com.kunzisoft.keepass.otp.OtpEntryFields.OTP_TOKEN_FIELD


class TemplateView @JvmOverloads constructor(context: Context,
                                                 attrs: AttributeSet? = null,
                                                 defStyle: Int = 0)
    : TemplateAbstractView(context, attrs, defStyle) {

    private var mOnAskCopySafeClickListener: (() -> Unit)? = null
    fun setOnAskCopySafeClickListener(listener: (() -> Unit)? = null) {
        this.mOnAskCopySafeClickListener = listener
    }
    private var mOnCopyActionClickListener: ((Field) -> Unit)? = null
    fun setOnCopyActionClickListener(listener: ((Field) -> Unit)? = null) {
        this.mOnCopyActionClickListener = listener
    }

    private var mFirstTimeAskAllowCopyProtectedFields: Boolean = false
    fun setFirstTimeAskAllowCopyProtectedFields(firstTimeAskAllowCopyProtectedFields : Boolean) {
        this.mFirstTimeAskAllowCopyProtectedFields = firstTimeAskAllowCopyProtectedFields
    }

    private var mAllowCopyProtectedFields: Boolean = false
    fun setAllowCopyProtectedFields(allowCopyProtectedFields : Boolean) {
        this.mAllowCopyProtectedFields = allowCopyProtectedFields
    }

    override fun buildHeader() {
        headerContainerView.isVisible = false
    }

    override fun buildLinearTextView(templateAttribute: TemplateAttribute,
                                     field: Field): View? {
        // Add an action icon if needed
        return context?.let {
            EntryFieldView(it).apply {
                applyFontVisibility(mFontInVisibility)
                setProtection(field.protectedValue.isProtected, mHideProtectedValue)
                label = TemplateField.getLocalizedName(context, field.name)
                setType(when (templateAttribute.type) {
                    TemplateAttributeType.SMALL_MULTILINE -> TextType.SMALL_MULTI_LINE
                    TemplateAttributeType.MULTILINE -> TextType.MULTI_LINE
                    else -> TextType.NORMAL
                })
                value = field.protectedValue.stringValue

                if (field.protectedValue.isProtected) {
                    if (mFirstTimeAskAllowCopyProtectedFields) {
                        setCopyButtonState(EntryFieldView.ButtonState.DEACTIVATE)
                        setCopyButtonClickListener {
                            mOnAskCopySafeClickListener?.invoke()
                        }
                    } else {
                        if (mAllowCopyProtectedFields) {
                            setCopyButtonState(EntryFieldView.ButtonState.ACTIVATE)
                            setCopyButtonClickListener {
                                mOnCopyActionClickListener?.invoke(field)
                            }
                        } else {
                            setCopyButtonState(EntryFieldView.ButtonState.GONE)
                            setCopyButtonClickListener(null)
                        }
                    }
                } else {
                    setCopyButtonState(EntryFieldView.ButtonState.ACTIVATE)
                    setCopyButtonClickListener {
                        mOnCopyActionClickListener?.invoke(field)
                    }
                }

                templateAttribute.options.forEach { option ->
                    // TODO options
                }
            }
        }
    }

    override fun buildDataTimeView(templateAttribute: TemplateAttribute,
                                   field: Field): View? {
        return context?.let {
            DateTimeView(it).apply {
                label = TemplateField.getLocalizedName(context, field.name)
                dateTime = try {
                    val value = field.protectedValue.toString()
                    activation = value.trim().isNotEmpty()
                    DateInstant(value,
                        when (templateAttribute.type) {
                            TemplateAttributeType.DATE -> DateInstant.Type.DATE
                            TemplateAttributeType.TIME -> DateInstant.Type.TIME
                            else -> DateInstant.Type.DATE_TIME
                        })
                } catch (e: Exception) {
                    activation = false
                    when (templateAttribute.type) {
                        TemplateAttributeType.DATE -> DateInstant.IN_ONE_MONTH_DATE
                        TemplateAttributeType.TIME -> DateInstant.IN_ONE_HOUR_TIME
                        else -> DateInstant.IN_ONE_MONTH_DATE_TIME
                    }
                }
            }
        }
    }

    override fun getActionImageView(): View? {
        return findViewWithTag<EntryFieldView?>(FIELD_PASSWORD_TAG)?.getCopyButtonView()
    }

    override fun populateViewsWithEntryInfo() {
        populateSpecificViewsWithEntryInfo<EntryFieldView, DateTimeView>(false) { emptyCustomFields ->
            // Hide empty custom fields
            emptyCustomFields.forEach { customFieldId ->
                templateContainerView.findViewById<View>(customFieldId.viewId)
                    .isVisible = false
            }
        }

        mEntryInfo?.let { entryInfo ->
            // Assign specific OTP dynamic view
            removeOtpRunnable()
            entryInfo.otpModel?.let {
                assignOtp(it)
            }
        }
    }

    override fun populateEntryInfoWithViews(templateFieldNotEmpty: Boolean) {
        populateSpecificEntryInfoWithViews<EntryFieldView, DateTimeView>(templateFieldNotEmpty)
    }

    override fun getCustomField(fieldName: String, templateFieldNotEmpty: Boolean): Field? {
        customFieldIdByName(fieldName)?.let { fieldId ->
            val editView: View? = templateContainerView.findViewById(fieldId.viewId)
                ?: customFieldsContainerView.findViewById(fieldId.viewId)
            if (editView is EntryFieldView) {
                if (!templateFieldNotEmpty ||
                    (editView.tag == FIELD_CUSTOM_TAG
                            && editView.value.isNotEmpty()))
                    return Field(fieldName, ProtectedString(fieldId.protected, editView.value))
            }
            if (editView is DateTimeView) {
                val value = if (editView.activation) editView.dateTime.toString() else ""
                if (!templateFieldNotEmpty ||
                    (editView.tag == FIELD_CUSTOM_TAG
                            && value.isNotEmpty()))
                    return Field(fieldName, ProtectedString(fieldId.protected, value))
            }
        }
        return null
    }

    /*
     * OTP Runnable
     */

    private var mOtpRunnable: Runnable? = null
    private var mLastOtpTokenView: View? = null

    fun setOnOtpElementUpdated(listener: ((OtpElement?) -> Unit)?) {
        this.mOnOtpElementUpdated = listener
    }
    private var mOnOtpElementUpdated: ((OtpElement?) -> Unit)? = null

    private fun getOtpTokenView(): EntryFieldView? {
        val indexFieldViewId = indexCustomFieldIdByName(OTP_TOKEN_FIELD)
        if (indexFieldViewId >= 0) {
            // Template contains the custom view
            val customFieldId = mCustomFieldIds[indexFieldViewId]
            return findViewById(customFieldId.viewId)
        }
        return null
    }

    private fun assignOtp(otpModel: OtpModel) {
        getOtpTokenView()?.apply {
            val otpElement = OtpElement(otpModel)
            if (otpElement.token.isEmpty()) {
                setLabel(R.string.entry_otp)
                setValue(R.string.error_invalid_OTP)
                setCopyButtonState(EntryFieldView.ButtonState.GONE)
            } else {
                label = otpElement.type.name
                value = otpElement.token
                setCopyButtonState(EntryFieldView.ButtonState.ACTIVATE)
                setCopyButtonClickListener {
                    mOnCopyActionClickListener?.invoke(Field(
                        otpElement.type.name,
                        ProtectedString(false, otpElement.token)))
                }
                mLastOtpTokenView = this
                mOtpRunnable = Runnable {
                    if (otpElement.shouldRefreshToken()) {
                        value = otpElement.token
                    }
                    if (mLastOtpTokenView == null) {
                        mOnOtpElementUpdated?.invoke(null)
                    } else {
                        mOnOtpElementUpdated?.invoke(otpElement)
                        postDelayed(mOtpRunnable, 1000)
                    }
                }
                mOnOtpElementUpdated?.invoke(otpElement)
                post(mOtpRunnable)
            }
        }
    }

    private fun removeOtpRunnable() {
        mLastOtpTokenView?.removeCallbacks(mOtpRunnable)
        mLastOtpTokenView = null
    }
}
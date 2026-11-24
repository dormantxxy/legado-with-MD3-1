package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.databinding.DialogAutoReadBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
//import io.legado.app.lib.theme.bottomBackground
//import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale


class AutoReadDialog : BaseBottomSheetDialogFragment(R.layout.dialog_auto_read) {

    private val binding by viewBinding(DialogAutoReadBinding::bind)
    private val callBack: CallBack? get() = activity as? CallBack

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return@run
        }
        initData()
        initEvent()
        initShakePage()
    }

    /**
     * 体感翻页（抖动手机翻页）开关，随开关即时生效
     */
    private fun initShakePage() = binding.run {
        switchAutoPage.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                (activity as? ReadBookActivity)?.startAutoPagerOnly()
            } else {
                (activity as? ReadBookActivity)?.stopAutoPagerOnly()
            }
        }

        switchShakePage.isChecked = AppConfig.shakePageTurn
        switchShakeBothNext.isChecked = AppConfig.shakePageBothNext
        switchShakeBothNext.isEnabled = AppConfig.shakePageTurn

        val sensitivity = AppConfig.shakePageSensitivity.coerceIn(1, 100)
        seekShakeSensitivity.value = sensitivity.toFloat()
        tvShakeSensitivity.text = sensitivity.toString()
        seekShakeSensitivity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) {
                return@addOnChangeListener
            }
            val v = value.toInt().coerceIn(1, 5)
            tvShakeSensitivity.text = v.toString()
            requireContext().putPrefString(
                io.legado.app.constant.PreferKey.shakePageSensitivity, v.toString()
            )
        }

        switchShakePage.setOnCheckedChangeListener { _, checked ->
            requireContext().putPrefBoolean(io.legado.app.constant.PreferKey.shakePageTurn, checked)
            (activity as? ReadBookActivity)?.setShakePageDetector(checked)
            switchShakeBothNext.isEnabled = checked
            llShakeSensitivity.isEnabled = checked
            seekShakeSensitivity.isEnabled = checked
        }
        llShakeSensitivity.isEnabled = AppConfig.shakePageTurn
        seekShakeSensitivity.isEnabled = AppConfig.shakePageTurn

        switchShakeBothNext.setOnCheckedChangeListener { _, checked ->
            requireContext().putPrefBoolean(io.legado.app.constant.PreferKey.shakePageBothNext, checked)
        }
    }

    private fun initData() {
        binding.switchAutoPage.isChecked =
            (activity as? ReadBookActivity)?.isAutoPagerRunning() ?: false
    }

    private fun initEvent() {
        binding.btnMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        binding.btnSetting.setOnClickListener {
            (activity as BaseReadBookActivity).showPageAnimConfig {
                (activity as ReadBookActivity).upPageAnim()
                ReadBook.loadContent(false)
            }
        }
        binding.btnCatalog.setOnClickListener { callBack?.openChapterList() }
        binding.btnAutoPageStop.setOnClickListener {
            callBack?.autoPageStop()
            binding.btnAutoPageStop.post {
                dismissAllowingStateLoss()
            }
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}
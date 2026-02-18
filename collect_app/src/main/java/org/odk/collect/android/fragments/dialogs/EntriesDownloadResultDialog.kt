package org.odk.collect.android.fragments.dialogs

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.odk.collect.android.R
import org.odk.collect.android.entrymanagement.EntryDownloadException
import org.odk.collect.android.entrymanagement.ServerEntryDetails
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.android.utilities.EntriesDownloadResultInterpreter
import org.odk.collect.errors.ErrorActivity
import java.io.Serializable

class EntriesDownloadResultDialog : DialogFragment() {
    private lateinit var result: Map<ServerEntryDetails, EntryDownloadException?>

    var listener: EntryDownloadResultDialogListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        DaggerUtils.getComponent(context).inject(this)
        if (context is EntryDownloadResultDialogListener) {
            listener = context
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        result = arguments?.getSerializable(ARG_RESULT) as Map<ServerEntryDetails, EntryDownloadException?>

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setMessage(getMessage())
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                listener?.onCloseDownloadingResult()
            }

        if (!EntriesDownloadResultInterpreter.allEntriesDownloadedSuccessfully(result)) {
            builder.setNegativeButton(getString(R.string.show_details)) { _, _ ->
                val intent = Intent(context, ErrorActivity::class.java).apply {
                    putExtra(ErrorActivity.EXTRA_ERRORS, EntriesDownloadResultInterpreter.getFailures(result, requireContext()) as Serializable)
                }
                startActivity(intent)
                listener?.onCloseDownloadingResult()
            }
        }

        return builder.create()
    }

    private fun getMessage(): String {
        return if (EntriesDownloadResultInterpreter.allEntriesDownloadedSuccessfully(result)) {
            getString(R.string.all_downloads_succeeded)
        } else {
            getString(R.string.some_downloads_failed, EntriesDownloadResultInterpreter.getNumberOfFailures(result).toString(), result.size.toString())
        }
    }

    interface EntryDownloadResultDialogListener {
        fun onCloseDownloadingResult()
    }

    companion object {
        const val ARG_RESULT = "RESULT"
    }
}

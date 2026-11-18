package com.tv.ball.presentation.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tv.ball.R
import com.tv.ball.data.KekeFilterOptions

/**
 * 可可影视筛选底部弹窗
 */
class KekeFilterBottomSheet : BottomSheetDialogFragment() {
    
    private var selectedType = ""
    private var selectedArea = ""
    private var selectedYear = ""
    private var selectedLang = ""
    
    private var onFilterApplied: ((type: String, area: String, year: String, lang: String) -> Unit)? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_keke_filter, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupTypeChips(view)
        setupAreaChips(view)
        setupYearChips(view)
        setupLangChips(view)
        setupButtons(view)
    }
    
    private fun setupTypeChips(view: View) {
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupType)
        KekeFilterOptions.types.forEach { (code, name) ->
            val chip = Chip(requireContext()).apply {
                text = name
                isCheckable = true
                isChecked = code == selectedType
                setOnClickListener {
                    selectedType = if (isChecked) code else ""
                }
            }
            chipGroup.addView(chip)
        }
    }
    
    private fun setupAreaChips(view: View) {
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupArea)
        KekeFilterOptions.areas.forEach { (code, name) ->
            val chip = Chip(requireContext()).apply {
                text = name
                isCheckable = true
                isChecked = code == selectedArea
                setOnClickListener {
                    selectedArea = if (isChecked) code else ""
                }
            }
            chipGroup.addView(chip)
        }
    }
    
    private fun setupYearChips(view: View) {
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupYear)
        KekeFilterOptions.years.forEach { (code, name) ->
            val chip = Chip(requireContext()).apply {
                text = name
                isCheckable = true
                isChecked = code == selectedYear
                setOnClickListener {
                    selectedYear = if (isChecked) code else ""
                }
            }
            chipGroup.addView(chip)
        }
    }
    
    private fun setupLangChips(view: View) {
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupLang)
        KekeFilterOptions.languages.forEach { (code, name) ->
            val chip = Chip(requireContext()).apply {
                text = name
                isCheckable = true
                isChecked = code == selectedLang
                setOnClickListener {
                    selectedLang = if (isChecked) code else ""
                }
            }
            chipGroup.addView(chip)
        }
    }
    
    private fun setupButtons(view: View) {
        view.findViewById<Button>(R.id.btnReset).setOnClickListener {
            selectedType = ""
            selectedArea = ""
            selectedYear = ""
            selectedLang = ""
            dismiss()
            onFilterApplied?.invoke("", "", "", "")
        }
        
        view.findViewById<Button>(R.id.btnApply).setOnClickListener {
            dismiss()
            onFilterApplied?.invoke(selectedType, selectedArea, selectedYear, selectedLang)
        }
    }
    
    fun setOnFilterAppliedListener(listener: (type: String, area: String, year: String, lang: String) -> Unit) {
        onFilterApplied = listener
    }
    
    companion object {
        fun newInstance(
            currentType: String = "",
            currentArea: String = "",
            currentYear: String = "",
            currentLang: String = ""
        ): KekeFilterBottomSheet {
            return KekeFilterBottomSheet().apply {
                selectedType = currentType
                selectedArea = currentArea
                selectedYear = currentYear
                selectedLang = currentLang
            }
        }
    }
}

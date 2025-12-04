package com.eneskocamaan.kenet.registration

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.CheckContactsRequest
import com.eneskocamaan.kenet.data.api.ContactModel
import com.eneskocamaan.kenet.databinding.FragmentContactSelectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactSelectionFragment : Fragment(R.layout.fragment_contact_selection) {

    private var _binding: FragmentContactSelectionBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ContactsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContactSelectionBinding.bind(view)

        setupRecyclerView()

        // Rehberi Yükle ve Sunucuyla Eşleştir
        loadAndCheckContacts()

        binding.btnConfirmSelection.setOnClickListener {
            val selectedContacts = adapter.getSelectedContacts()
            setFragmentResult("requestKey_contacts", bundleOf("selected_contacts" to ArrayList(selectedContacts)))
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter { count ->
            binding.btnConfirmSelection.text = "Seçimi Onayla ($count)"
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = adapter
    }

    @SuppressLint("Range")
    private fun loadAndCheckContacts() {
        binding.pbLoading.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Yerel Rehberi Çek (Temizlenmiş numaralarla)
            val localContacts = getLocalContacts()

            if (localContacts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.pbLoading.visibility = View.GONE
                    Toast.makeText(context, "Rehberde kişi bulunamadı.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                // 2. Numaraları Listele (Temizlenmiş formatta)
                val phoneNumbersToSend = localContacts.map { it.phone_number }

                // 3. Backend'e Sorgu At
                val request = CheckContactsRequest(phoneNumbersToSend)
                val response = ApiClient.api.checkContacts(request)

                if (response.isSuccessful && response.body() != null) {
                    val registeredNumbers = response.body()!!.registeredNumbers

                    // 4. Listeyi Güncelle: Eşleşenleri işaretle
                    val updatedList = localContacts.map { contact ->
                        // İki taraf da temiz olduğu için (532...) eşleşme başarılı olur.
                        if (registeredNumbers.contains(contact.phone_number)) {
                            contact.isKenetUser = true
                        }
                        contact
                    }.sortedByDescending { it.isKenetUser } // Kenet kullananlar en üstte

                    withContext(Dispatchers.Main) {
                        if (_binding != null) adapter.submitList(updatedList)
                    }
                } else {
                    // API Hatası varsa normal listeyi göster
                    withContext(Dispatchers.Main) {
                        if (_binding != null) adapter.submitList(localContacts)
                    }
                }

            } catch (e: Exception) {
                // İnternet yoksa normal listeyi göster
                withContext(Dispatchers.Main) {
                    if (_binding != null) adapter.submitList(localContacts)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.pbLoading.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Numarayı veritabanı formatına (10 hane, başında 0 yok) çevirir.
     * Örn: +90 532 123 -> 532123
     */
    private fun sanitizePhoneNumber(phone: String): String {
        // 1. Sadece rakamları al (+ işaretini atar)
        var p = phone.replace("[^0-9]".toRegex(), "")

        // 2. 90 ile başlıyorsa (ülke kodu) sil
        if (p.startsWith("90") && p.length > 10) {
            p = p.substring(2)
        }

        // 3. Başındaki '0'ı sil
        if (p.startsWith("0")) {
            p = p.substring(1)
        }

        return p
    }

    @SuppressLint("Range")
    private fun getLocalContacts(): ArrayList<ContactModel> {
        val contactList = ArrayList<ContactModel>()
        val cursor = requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val rawPhone = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))

                if (!name.isNullOrEmpty() && !rawPhone.isNullOrEmpty()) {
                    // DÜZELTME: Listeye eklemeden önce numarayı temizliyoruz
                    val cleanPhone = sanitizePhoneNumber(rawPhone)

                    // Geçerli bir numara mı (En az 10 hane) ve tekrar ediyor mu?
                    if (cleanPhone.length >= 10 && !contactList.any { c -> c.phone_number == cleanPhone }) {
                        contactList.add(
                            ContactModel(
                                phone_number = cleanPhone,
                                display_name = name
                            )
                        )
                    }
                }
            }
        }
        return contactList
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
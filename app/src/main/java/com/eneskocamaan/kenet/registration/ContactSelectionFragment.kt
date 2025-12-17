package com.eneskocamaan.kenet.registration

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
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
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.databinding.FragmentContactSelectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactSelectionFragment : Fragment(R.layout.fragment_contact_selection) {

    private var _binding: FragmentContactSelectionBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ContactsAdapter

    // Veritabanı referansı
    private val db by lazy { AppDatabase.getDatabase(requireContext()) }

    // Orijinal listeyi hafızada tutuyoruz, arama yapınca filtreleyip buna döneceğiz
    private var fullContactList = listOf<ContactModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContactSelectionBinding.bind(view)

        setupRecyclerView()
        setupSearch() // Arama dinleyicisi

        // Rehberi Yükle, DB ile eşleştir ve Sunucuyla Kontrol Et
        loadAndCheckContacts()

        binding.btnConfirmSelection.setOnClickListener {
            // Sadece görünür olanlar değil, ana listedeki seçili olanları al
            val selectedContacts = fullContactList.filter { it.isSelected }
            setFragmentResult("requestKey_contacts", bundleOf("selected_contacts" to ArrayList(selectedContacts)))
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter { count ->
            // Arama yaparken liste küçüldüğü için, toplam seçili sayısını
            // ana liste üzerinden hesaplamak daha doğru olur.
            val totalSelected = fullContactList.count { it.isSelected }
            binding.btnConfirmSelection.text = "Seçimi Onayla ($totalSelected)"
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = adapter
    }

    // --- 1. ARAMA MANTIĞI ---
    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterList(query: String) {
        val lowerQuery = query.lowercase().trim()

        if (lowerQuery.isEmpty()) {
            adapter.submitList(fullContactList)
        } else {
            val filteredList = fullContactList.filter { contact ->
                // İsimde VEYA numarada ara
                contact.display_name.lowercase().contains(lowerQuery) ||
                        contact.phone_number.contains(lowerQuery)
            }
            adapter.submitList(filteredList)
        }
    }

    @SuppressLint("Range")
    private fun loadAndCheckContacts() {
        binding.pbLoading.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            // A. Veritabanındaki (zaten ekli) kişileri çek
            val existingContacts = db.contactDao().getAllContactsList() // Dao'ya bu metodu eklemen gerekebilir (List dönecek)
            val existingNumbers = existingContacts.map { it.contactPhoneNumber } // Sadece numaraları al

            // B. Yerel Rehberi Çek
            val localContacts = getLocalContacts()

            if (localContacts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.pbLoading.visibility = View.GONE
                    Toast.makeText(context, "Rehberde kişi bulunamadı.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // --- 2. ZATEN EKLİ OLANLARI İŞARETLE (TIK KOY) ---
            localContacts.forEach { contact ->
                if (existingNumbers.contains(contact.phone_number)) {
                    contact.isSelected = true
                }
            }

            try {
                // C. Backend Kontrolü (Kenet Kullanıcısı mı?)
                val phoneNumbersToSend = localContacts.map { it.phone_number }
                val request = CheckContactsRequest(phoneNumbersToSend)
                val response = ApiClient.api.checkContacts(request)

                if (response.isSuccessful && response.body() != null) {
                    val registeredNumbers = response.body()!!.registeredNumbers

                    // Kenet kullananları işaretle
                    localContacts.forEach { contact ->
                        if (registeredNumbers.contains(contact.phone_number)) {
                            contact.isKenetUser = true
                        }
                    }
                }
            } catch (e: Exception) {
                // Hata olursa devam et (Sadece Kenet rozeti gözükmez)
            }

            // D. Listeyi Sırala:
            // 1. Kenet Kullanıcıları -> 2. Alfabetik
            val sortedList = localContacts.sortedWith(
                compareByDescending<ContactModel> { it.isKenetUser }
                    .thenBy { it.display_name }
            )

            // Ana listeyi güncelle
            fullContactList = sortedList

            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    adapter.submitList(fullContactList)

                    // Başlangıçta seçili sayısını butona yaz
                    val count = fullContactList.count { it.isSelected }
                    binding.btnConfirmSelection.text = "Seçimi Onayla ($count)"

                    binding.pbLoading.visibility = View.GONE
                }
            }
        }
    }

    // ... (sanitizePhoneNumber ve getLocalContacts metodları aynen kalacak) ...
    private fun sanitizePhoneNumber(phone: String): String {
        var p = phone.replace("[^0-9]".toRegex(), "")
        if (p.startsWith("90") && p.length > 10) p = p.substring(2)
        if (p.startsWith("0")) p = p.substring(1)
        return p
    }

    @SuppressLint("Range")
    private fun getLocalContacts(): ArrayList<ContactModel> {
        val contactList = ArrayList<ContactModel>()
        val cursor = requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val rawPhone = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))

                if (!name.isNullOrEmpty() && !rawPhone.isNullOrEmpty()) {
                    val cleanPhone = sanitizePhoneNumber(rawPhone)
                    if (cleanPhone.length >= 10 && !contactList.any { c -> c.phone_number == cleanPhone }) {
                        contactList.add(ContactModel(phone_number = cleanPhone, display_name = name))
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
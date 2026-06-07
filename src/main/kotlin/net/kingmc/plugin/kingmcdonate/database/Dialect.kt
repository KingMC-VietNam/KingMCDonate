package net.kingmc.plugin.kingmcdonate.database

/**
 * The two supported SQL dialects. Only the few places where SQLite and MySQL
 * differ (auto-increment primary keys) are abstracted here; everything else in
 * the schema uses portable types (BIGINT for time/amounts, INT for booleans).
 */
enum class Dialect {
    SQLITE {
        override val autoIncrementPk = "INTEGER PRIMARY KEY AUTOINCREMENT"
    },
    MYSQL {
        override val autoIncrementPk = "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
    };

    /** Column definition (excluding the column name) for an auto-increment surrogate key. */
    abstract val autoIncrementPk: String

    companion object {
        fun fromType(type: String): Dialect = if (type.equals("mysql", ignoreCase = true)) MYSQL else SQLITE
    }
}

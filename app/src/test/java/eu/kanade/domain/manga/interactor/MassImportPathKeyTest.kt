package eu.kanade.domain.manga.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A plugin-returned path is stored verbatim, so mass import reconciles the user's pasted spelling
 * against the stored one with a comparison key instead of rewriting either.
 */
class MassImportPathKeyTest {

    @Test
    fun `compare key ignores only the trailing slash`() {
        assertEquals("/novel/abc", MassImport.pathCompareKey("/novel/abc"))
        assertEquals("/novel/abc", MassImport.pathCompareKey("/novel/abc/"))
        // Case and inner slashes are identity, not formatting: they must survive.
        assertEquals("/Novel//Abc", MassImport.pathCompareKey("/Novel//Abc"))
        assertEquals("/novel/abc?p=2", MassImport.pathCompareKey("/novel/abc?p=2"))
    }

    @Test
    fun `degenerate paths keep their key rather than collapsing to empty`() {
        assertEquals("/", MassImport.pathCompareKey("/"))
        assertEquals("", MassImport.pathCompareKey(""))
    }

    @Test
    fun `variant flips the trailing slash both ways`() {
        assertEquals("/novel/abc/", MassImport.trailingSlashVariant("/novel/abc"))
        assertEquals("/novel/abc", MassImport.trailingSlashVariant("/novel/abc/"))
    }

    @Test
    fun `variant is null when there is nothing worth probing`() {
        assertNull(MassImport.trailingSlashVariant("/"))
        assertNull(MassImport.trailingSlashVariant(""))
        // "//" trims to empty: probing the root is never a duplicate match worth a query.
        assertNull(MassImport.trailingSlashVariant("//"))
    }
}

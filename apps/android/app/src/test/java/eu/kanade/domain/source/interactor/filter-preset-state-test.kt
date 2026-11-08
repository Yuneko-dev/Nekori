@file:Suppress("ktlint:standard:filename")

package eu.kanade.domain.source.interactor

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FilterPresetStateTest {
    private fun text(name: String, value: String = "") = object : Filter.Text(name, value) {}
    private fun check(name: String, value: Boolean = false) = object : Filter.CheckBox(name, value) {}
    private fun tri(name: String, value: Int = 0) = object : Filter.TriState(name, value) {}
    private fun select(name: String, vararg values: String) = object : Filter.Select<String>(name, arrayOf(*values)) {}
    private fun sort(state: Filter.Sort.Selection? = null) = object : Filter.Sort("Sort", arrayOf("A", "B"), state) {}
    private fun restore(filters: FilterList, state: String) = ManageFilterPresets.applyPresetState(filters, state)
    private fun saved(vararg filters: Filter<*>) = ManageFilterPresets.serializeFilters(FilterList(*filters))

    @Test
    fun `filters match names after addition removal and reorder`() {
        val state = saved(text("Query", "saved"), check("Enabled", true), text("Removed", "gone"))
        val enabled = check("Enabled")
        val query = text("Query")
        val added = text("New", "default")
        restore(FilterList(added, enabled, query), state)
        assertEquals("saved", query.state)
        assertEquals(true, enabled.state)
        assertEquals("default", added.state)
    }

    @Test
    fun `select follows option name rather than old index`() {
        val old = select("Status", "All", "Complete").apply { state = 1 }
        val current = select("Status", "Complete", "All")
        restore(FilterList(current), saved(old))
        assertEquals(0, current.state)
        val removed = select("Status", "All", "Ongoing")
        restore(FilterList(removed), saved(old))
        assertEquals(0, removed.state)
    }

    @Test
    fun `legacy presets match names and validate indices and types independently`() {
        val query = text("Query")
        val status = select("Status", "All", "Done")
        val enabled = check("Enabled", true)
        restore(
            FilterList(status, query, enabled),
            """[{"index":0,"name":"Query","state":"saved"},17,
                {"index":-1,"name":"Status","state":99},
                {"name":"Enabled","state":"false"}]""",
        )
        assertEquals("saved", query.state)
        assertEquals(0, status.state)
        assertEquals(true, enabled.state)
    }

    @Test
    fun `same-name filters of different kinds do not consume each other`() {
        val state = saved(text("Shared", "words"), check("Shared", true), tri("Number", 2), select("Number", "A", "B"))
        val box = check("Shared")
        val query = text("Shared")
        val choice = select("Number", "A", "B")
        val mode = tri("Number")
        restore(FilterList(box, query, choice, mode), state)
        assertEquals("words", query.state)
        assertEquals(true, box.state)
        assertEquals(2, mode.state)
        assertEquals(0, choice.state)
    }

    @Test
    fun `ambiguous legacy numeric names are skipped rather than guessed`() {
        val choice = select("Shared", "A", "B")
        val mode = tri("Shared")
        restore(FilterList(choice, mode), """[{"index":0,"name":"Shared","state":1}]""")
        assertEquals(0, choice.state)
        assertEquals(0, mode.state)
    }

    @Test
    fun `null sort survives copy and invalid sort is ignored`() {
        val current = sort(Filter.Sort.Selection(1, false))
        restore(FilterList(current), saved(sort()))
        assertNull(current.state)
        restore(FilterList(current), """[{"name":"Sort","state":{"index":-1,"ascending":true}}]""")
        assertNull(current.state)
    }

    @Test
    fun `malformed group item does not stop later valid items`() {
        val first = check("First")
        val second = tri("Second")
        val group = object : Filter.Group<Filter<*>>("Group", listOf(second, first)) {}
        restore(
            FilterList(group),
            """[{"name":"Group","state":[null,{"name":"First","state":true},
            {"name":"Second","state":7},{"name":"Second","state":2}]}]""",
        )
        assertEquals(true, first.state)
        assertEquals(2, second.state)
    }

    @Test
    fun `JS filters retain runtime values when options and groups reorder`() {
        val old = JsSource.JsSelectFilter("Status", "status", listOf("All" to "all", "Done" to "done"), 1)
        val oldGroup = JsSource.JsCheckboxGroup(
            "Genres",
            "genres",
            listOf(
                JsSource.JsCheckbox("A", "a").apply {
                    state =
                        true
                },
            ),
        )
        val oldTri = JsSource.JsTriStateGroup("Tags", "tags", listOf(JsSource.JsTriState("X", "x").apply { state = 2 }))
        val choice = JsSource.JsSelectFilter("Status", "status", listOf("Done" to "done", "All" to "all"))
        val group = JsSource.JsCheckboxGroup(
            "Genres",
            "genres",
            listOf(JsSource.JsCheckbox("B", "b"), JsSource.JsCheckbox("A", "a")),
        )
        val tags = JsSource.JsTriStateGroup("Tags", "tags", listOf(JsSource.JsTriState("X", "x")))
        restore(FilterList(tags, group, choice), saved(old, oldGroup, oldTri))
        assertEquals("done", choice.selectedValue())
        assertEquals(listOf("a"), group.selectedValues())
        assertEquals(listOf("x"), tags.excludedValues())
        group.checkboxes.last().state = false
        assertEquals(listOf("a"), oldGroup.selectedValues(), "editing a draft must not mutate active filters")
    }

    @Test
    fun `legacy selected index still works when valid`() {
        val choice = select("Status", "All", "Done")
        restore(FilterList(choice), """[{"name":"Status","index":10,"state":1}]""")
        assertEquals(1, choice.state)
    }

    @Test
    fun `Unicode and duplicate option labels retain their saved selection`() {
        val old = select("Trạng thái 中文", "Đã xong", "Đã xong").apply { state = 1 }
        val current = select("Trạng thái 中文", "Đã xong", "Đã xong")
        restore(FilterList(current), saved(old))
        assertEquals(1, current.state)
    }

    @Test
    fun `unnamed option identity falls back only to a valid index`() {
        val current = select("Status", "A", "B")
        restore(FilterList(current), """[{"name":"Status","state":1,"value":"Option@abc"}]""")
        assertEquals(1, current.state)
        restore(FilterList(current), """[{"name":"Status","state":-1,"value":"Option@def"}]""")
        assertEquals(1, current.state)
    }

    @Test
    fun `removing one of duplicate saved identities does not guess the survivor`() {
        val state = saved(check("Shared", false), check("Shared", true))
        val survivor = check("Shared", true)
        restore(FilterList(survivor), state)
        assertEquals(true, survivor.state)
        val oldGroup = object : Filter.Group<Filter<*>>("Group", listOf(check("Same", false), check("Same", true))) {}
        val child = check("Same", true)
        val newGroup = object : Filter.Group<Filter<*>>("Group", listOf(child)) {}
        restore(FilterList(newGroup), saved(oldGroup))
        assertEquals(true, child.state)
    }
}

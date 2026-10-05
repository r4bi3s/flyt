package no.heimflyt.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

data class ShortcutEntry(val packageName:String,val id:String,val label:String,val userSerial:Long)
data class ContactEntry(val id:Long,val lookup:String,val name:String,val number:String?=null,val email:String?=null) {
    val uri:Uri get()=ContactsContract.Contacts.getLookupUri(id,lookup)
    val detail:String? get()=number ?: email
}
data class ContactGroup(val name:String,val entries:List<ContactEntry>)

/** Several Android contact records can describe one displayed person. Keep every record for the action sheet. */
fun groupContacts(entries:List<ContactEntry>):List<ContactGroup> = entries
    .groupBy { it.name.trim().lowercase() }
    .values.map { matches -> ContactGroup(matches.first().name,matches.distinctBy { it.id }) }

data class SourceFlags(val apps:Boolean=true,val contacts:Boolean=true,val actions:Boolean=true)

/**
 * A tag's human-readable name: trimmed, a leading # dropped, inner whitespace collapsed to one space, case kept
 * ("  På   farten " → "På farten"). Null when nothing is left.
 */
fun tagDisplayName(raw:String)=raw.trim().removePrefix("#").trim().replace(Regex("\\s+")," ").take(32).takeIf { it.isNotEmpty() }

/**
 * A tag's stable identity (the key in assignments, Browse slots and radial bindings) and its typed Search form:
 * the display name lowercased with spaces as '-' ("På farten" → "på-farten", typed as #på-farten). Norwegian letters
 * are kept. Idempotent, so every pre-H6 tag (already lowercase, no spaces) is its own identity unchanged.
 */
fun normalizeTagName(raw:String)=tagDisplayName(raw)?.lowercase(java.util.Locale.ROOT)?.replace(' ','-')
    ?.takeIf { it.matches(Regex("[\\p{L}\\p{N}_-]+")) }

fun resolveTag(prefix:String,names:List<String>):String? = when {
    prefix in names -> prefix
    else -> names.filter { it.startsWith(prefix) }.singleOrNull()
}

class SearchSources(context:Context) {
    private val prefs=context.getSharedPreferences("search_sources",Context.MODE_PRIVATE)
    fun read()=SourceFlags(prefs.getBoolean("apps",true),prefs.getBoolean("contacts",true),prefs.getBoolean("actions",true))
    fun save(flags:SourceFlags) { prefs.edit().putBoolean("apps",flags.apps).putBoolean("contacts",flags.contacts).putBoolean("actions",flags.actions).apply() }
}

/** Tags are local app metadata, keyed by stable launcher component and profile serial. */
class TagStore(context:Context, private val onUnassigned: (String, String) -> Unit = { _, _ -> }) {
    private val prefs=context.getSharedPreferences("app_tags",Context.MODE_PRIVATE)
    var names:List<String> = emptyList(); private set
    var assignments:Map<String,Set<String>> = emptyMap(); private set
    /** Display names that differ from their identity ("på-farten" → "På farten"). Absent for plain tags. */
    var labels:Map<String,String> = emptyMap(); private set
    init { try {
        val root=JSONObject(prefs.getString("data",null) ?: "{}")
        names=List(root.optJSONArray("names")?.length() ?: 0) { root.getJSONArray("names").getString(it) }.distinct().sorted()
        val apps=root.optJSONObject("apps")
        assignments=apps?.keys()?.asSequence()?.associateWith { key ->
            val a=apps.getJSONArray(key); (0 until a.length()).map { a.getString(it) }.toSet()
        } ?: emptyMap()
        val l=root.optJSONObject("labels")
        labels=l?.keys()?.asSequence()?.filter { it in names }?.associateWith { l.getString(it) }?.filter { (id,label)-> normalizeTagName(label)==id } ?: emptyMap()
    } catch (_:Exception) { names=emptyList(); assignments=emptyMap(); labels=emptyMap() } }
    private fun persist() { val root=JSONObject().put("names",JSONArray(names)); val apps=JSONObject()
        assignments.forEach { (k,v)-> apps.put(k,JSONArray(v.toList())) }; root.put("apps",apps)
        // Written only when a tag has a display name, so data without one keeps its exact pre-H6 form.
        if(labels.isNotEmpty()) root.put("labels",JSONObject(labels))
        prefs.edit().putString("data",root.toString()).apply()
    }
    fun normalize(raw:String)=normalizeTagName(raw)
    /** The name to show for tag [id]. */
    fun label(id:String)=labels[id] ?: id
    private fun labelled(id:String,raw:String):Map<String,String> { val d=tagDisplayName(raw); return if(d==null || d==id) labels-id else labels+(id to d) }
    /** "På farten", "på farten" and " På  farten " are one tag; creating an existing one changes nothing. */
    fun create(raw:String):Boolean { val n=normalize(raw) ?: return false; if(n in names)return true; names=(names+n).sorted(); labels=labelled(n,raw);persist();return true }
    fun set(appKey:String,tag:String,assigned:Boolean) { if(tag !in names)return
        val next=assignments.toMutableMap(); val values=next[appKey].orEmpty().toMutableSet()
        if(assigned) values+=tag else values-=tag
        if(values.isEmpty()) next.remove(appKey) else next[appKey]=values
        assignments=next;persist()
        if (!assigned) onUnassigned(tag, appKey)
    }
    fun rename(old:String,raw:String):Boolean { val n=normalize(raw) ?: return false; if(old !in names || (n!=old && n in names))return false
        names=(names-old+n).distinct().sorted(); assignments=assignments.mapValues { (_,v)-> if(old in v) v-old+n else v }
        labels=labels-old; labels=labelled(n,raw);persist();return true
    }
    fun delete(tag:String) { names=names-tag; assignments=assignments.mapValues { (_,v)->v-tag }.filterValues { it.isNotEmpty() }; labels=labels-tag;persist() }
    fun tags(appKey:String)=assignments[appKey].orEmpty()
}

data class ParsedSearch(val tag:String?,val text:String) {
    companion object { fun from(input:String):ParsedSearch { val q=input.trim(); if(!q.startsWith("#"))return ParsedSearch(null,q)
        val parts=q.drop(1).split(Regex("\\s+"),limit=2)
        return ParsedSearch(parts.firstOrNull()?.lowercase() ?: "",parts.getOrNull(1)?.trim().orEmpty())
    } }
}

fun matchScore(label:String,query:String):Int { if(query.isBlank())return 3
    val l=label.lowercase(); val q=query.lowercase(); return when { l==q->0; l.startsWith(q)->1; l.contains(q)->2; else->Int.MAX_VALUE }
}

class ContactSearch(private val context:Context) {
    fun permitted()=ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
    suspend fun find(query:String):List<ContactEntry> = withContext(Dispatchers.IO) {
        if(!permitted() || query.isBlank()) return@withContext emptyList()
        val signal=CancellationSignal()
        coroutineContext.job.invokeOnCompletion { signal.cancel() }
        val results=linkedMapOf<Long,ContactEntry>()
        fun collect(uri:Uri,detailColumn:String?=null,email:Boolean=false) {
            context.contentResolver.query(uri,arrayOf(ContactsContract.Contacts._ID,ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY).let { if(detailColumn==null)it else it+detailColumn },null,null,null,signal)?.use { c ->
                val idCol=c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val keyCol=c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameCol=c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val detailCol=detailColumn?.let { c.getColumnIndex(it) } ?: -1
                var scanned=0
                while(c.moveToNext() && scanned++<120) {
                    val id=c.getLong(idCol)
                    if(results.size>=60 && id !in results) continue
                    val key=c.getString(keyCol) ?: continue
                    val name=c.getString(nameCol) ?: continue
                    val detail=if(detailCol>=0) c.getString(detailCol) else null
                    val previous=results[id]
                    results[id]=ContactEntry(id,key,name,
                        if(email) previous?.number else detail ?: previous?.number,
                        if(email) detail ?: previous?.email else previous?.email)
                }
            }
        }
        fun safeCollect(uri:Uri,detailColumn:String?=null,email:Boolean=false) {
            coroutineContext.ensureActive()
            try { collect(uri,detailColumn,email) }
            catch (_:SecurityException) { results.clear() }
            catch (_:RuntimeException) { /* A missing optional filter must not hide other matches. */ }
        }
        safeCollect(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI,Uri.encode(query)))
        safeCollect(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,Uri.encode(query)),ContactsContract.CommonDataKinds.Phone.NUMBER)
        safeCollect(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,Uri.encode(query)),ContactsContract.CommonDataKinds.Email.ADDRESS,true)
        coroutineContext.ensureActive()
        results.values.sortedWith(compareBy({matchScore(it.name,query)},{it.name.lowercase()})).take(60)
    }
    suspend fun details(entries:List<ContactEntry>):ContactOptions=withContext(Dispatchers.IO) {
        if(!permitted()) return@withContext ContactOptions()
        val ids=entries.map { it.id }.distinct().take(12)
        fun values(uri:Uri,detailColumn:String):List<String> {
            val found=linkedMapOf<String,String>()
            ids.forEach { id ->
                val signal=CancellationSignal()
                coroutineContext.job.invokeOnCompletion { signal.cancel() }
                try { context.contentResolver.query(uri,arrayOf(detailColumn),
                    "${ContactsContract.Data.CONTACT_ID}=?",arrayOf(id.toString()),null,signal)?.use { cursor ->
                    while(cursor.moveToNext() && found.size<12) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { value ->
                        val key=if(detailColumn==ContactsContract.CommonDataKinds.Phone.NUMBER)
                            value.filter(Char::isDigit).ifBlank { value } else value.lowercase()
                        found.putIfAbsent(key,value)
                    }
                } } catch (_:RuntimeException) { }
                coroutineContext.ensureActive()
            }
            return found.values.toList()
        }
        ContactOptions(
            values(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,ContactsContract.CommonDataKinds.Phone.NUMBER),
            values(ContactsContract.CommonDataKinds.Email.CONTENT_URI,ContactsContract.CommonDataKinds.Email.ADDRESS),
        )
    }
}

data class ContactOptions(val numbers:List<String> = emptyList(),val emails:List<String> = emptyList())

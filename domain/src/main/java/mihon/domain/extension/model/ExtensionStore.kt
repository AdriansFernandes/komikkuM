package mihon.domain.extension.model

data class ExtensionStore(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val signingKey: String,
    val contact: Contact,
    val isLegacy: Boolean,
    val extensionListUrl: String?,
) {
    data class Contact(
        val website: String,
        val discord: String?,
    )
}

const val REPO_HELP = "https://komikku-app.github.io/docs/guides/getting-started#adding-sources"

// cuong-tran's key
const val KOMIKKU_SIGNATURE = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"
const val REPO_SIGNATURE = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

// KMK -->
const val CURSED_SIGNATURE = "a5e2027162d4b7294d54fd069af923eee4078cc097765d3a0d84a747d3a8356e"
const val KEIYOUSHI_REPO_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
const val CURSED_REPO_URL = "https://raw.githubusercontent.com/mojuru/cursed-manga-repo/repo/index.pb"

val DEFAULT_EXTENSION_STORES = listOf(
    ExtensionStore(
        indexUrl = KEIYOUSHI_REPO_URL,
        name = "Keiyoushi",
        badgeLabel = "Keiyoushi",
        signingKey = REPO_SIGNATURE,
        contact = ExtensionStore.Contact(
            website = "https://keiyoushi.github.io",
            discord = "https://discord.gg/3FbCpdKbdY",
        ),
        isLegacy = false,
        extensionListUrl = null,
    ),
    ExtensionStore(
        indexUrl = CURSED_REPO_URL,
        name = "Cursed Yūzōnō",
        badgeLabel = "Cursed",
        signingKey = CURSED_SIGNATURE,
        contact = ExtensionStore.Contact(
            website = "https://github.com/mojuru/cursed-manga-repo",
            discord = null,
        ),
        isLegacy = false,
        extensionListUrl = null,
    ),
)
// KMK <--

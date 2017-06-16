import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun main(args: Array<String>) {
    val forge = readJoined(lineSequence(Paths.get("forge","1.8","stable_18","notch-srg.srg")))
    val bukkit = readCraftBukkit(Paths.get("craftbukkit", "1.8"))
    val reverseForge = forge.reverse()
    val forge2bukkit = bridgeForgeToBukkit(forge, bukkit)
    Unit
}

interface Reversible<T> {
    fun reverse(): T
}

interface ReversibleData<T> : Reversible<T> {
    val from: String
    val to: String
    fun copy(from: String, to: String): T
    override fun reverse() = copy(from = to, to = from)
}

data class Package(val from: String, val to: String) : Reversible<Package> { override fun reverse() = copy(from = to, to = from) }
data class Class(val from: String, val to: String) : Reversible<Class> { override fun reverse() = copy(from = to, to = from) }
data class Field(val from: String, val to: String) : Reversible<Field> { override fun reverse() = copy(from = to, to = from) }
data class Method(val from: String, val to: String) : Reversible<Method> { override fun reverse() = copy(from = to, to = from) }

class Mappings: Reversible<Mappings> {
    val packages = HashMap<String, Package>()
    val classes = HashMap<String, Class>()
    val fields = HashMap<String, Field>()
    val methods = HashMap<String, Method>()

    override fun reverse(): Mappings {
        return Mappings().also {
            it.packages += packages.map { (_, `package`) -> `package`.to to `package`.reverse() }
            it.classes += classes.map { (_, `class`) -> `class`.to to `class`.reverse() }
            it.fields += fields.map { (_, field) -> field.to to field.reverse() }
            it.methods += methods.map { (_, method) -> method.to to method.reverse() }
        }
    }
}

fun bridgeForgeToBukkit(forge: Mappings, bukkit: Mappings): Mappings {
    return Mappings().also {
        it.packages += forge.packages.map { (from, orig) ->
            bukkit.packages[from]?.copy(from = orig.to)?.let { it.from to it }
                    ?: orig.to to orig.copy(from = orig.to)
        }

        it.classes += forge.classes.map { (from, orig) ->
            bukkit.classes[from]?.copy(from = orig.to)?.let { it.from to it }
                    ?: orig.to to orig.copy(from = orig.to)
        }

        fun resolveClassPair(from: String, to: String): Pair<String, String> {
            val fromClassName = to.substringBeforeLast('/')
            val toClassName = it.classes[fromClassName]?.to ?: fromClassName
            return to to toClassName+'/'+from.substringAfterLast('/')
        }

        it.fields += forge.fields.map { (from, orig) ->
            bukkit.fields[from]?.copy(from = orig.to)?.let { return@map it.from to it }
            val result = resolveClassPair(from, orig.to).let { Field(it.first, it.second) }
            result.from to result
        }

        it.methods += forge.methods.map { (from, orig) ->
            //bukkit.methods[from]?.copy(from = orig.to)?.let { it.from to it }
            bukkit.methods[from]?.copy(from = orig.to)?.let {
                return@map it.from to it
            }
            val pairTokens = resolveClassPair(from.substringBefore(' '), orig.to.substringBefore(' '))
            val result = Method(
                    pairTokens.first+' '+orig.to.substringAfter(' '),
                    pairTokens.second+' '+classType.replace(from.substringAfter(' ')) { matcher ->
                        'L'+(bukkit.classes[matcher.groupValues[1]]?.to ?: matcher.groupValues[1])+';'
                    }
            )
            result.from to result
        }
    }
}

fun lineSequence(path: Path) = Files.readAllLines(path).asSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith('#') }

val spacer = Regex(" +")

fun String.noDot() = if(this == ".") "" else this

fun readJoined(lines: Sequence<String>): Mappings {
    return Mappings().apply {
        lines.forEach { line ->
            val tokens = line.trim().split(spacer)
            when (tokens[0]) {
                "CL:" -> classes[tokens[1]] = Class(tokens[1], tokens[2])
                "FD:" -> fields[tokens[1]] = Field(tokens[1], tokens[2])
                "MD:" -> methods[tokens[1]+' '+tokens[2]] = Method(tokens[1]+' '+tokens[2], tokens[3]+' '+tokens[4])
                "PK:" -> {
                    packages[tokens[1].noDot()] = Package(tokens[1].noDot(), tokens[2].noDot())
                }
            }
        }
    }
}

val classType = Regex("L([^;]+);")
fun readCraftBukkit(dir: Path): Mappings {
    val version = dir.fileName.toString()
    val packageVersion = Files.readAllLines(dir.resolve("version.txt"))[0].trim()
    return Mappings().apply {
        lineSequence(dir.resolve("package.srg")).forEach {
            val tokens = it.split(spacer).map { it.substringBeforeLast('/') }
            packages[tokens[0].noDot()] = Package(tokens[0].noDot(), "${tokens[1]}/$packageVersion")
        }

        val reverse = HashMap<String, Class>()
        lineSequence(dir.resolve("bukkit-$version-cl.csrg")).forEach {
            val tokens = it.split(spacer)
            val `class`= Class(tokens[0], "net/minecraft/server/$packageVersion/${tokens[1]}")
            classes[tokens[0]] = `class`
            reverse[tokens[1]] = `class`
        }

        fun MutableMap<String, Class>.getOrRegister(name: String) = getOrPut(name) {
            Class(name, name.replace("net/minecraft/server/", "net/minecraft/server/$packageVersion/")).also {
                classes[it.from] = it
                reverse[name] = it
            }
        }

        fun fromSignature(signature: String): String {
            return signature.replace(classType) { "L${reverse.getOrRegister(it.groupValues[1]).from};" }
        }

        fun toSignature(signature: String): String {
            return signature.replace(classType) { "L${reverse.getOrRegister(it.groupValues[1]).to};" }
        }

        lineSequence(dir.resolve("bukkit-$version-members.csrg")).forEach {
            val tokens = it.split(spacer)
            val fromClass = reverse.getOrRegister(tokens[0])

            val from = "${fromClass.from}/${tokens[1]}"
            if(tokens.size == 3) {
                fields[from] = Field(from, "${fromClass.to}/${tokens[2]}")
            }
            else {
                val reverseSignature = from+' '+fromSignature(tokens[2])
                methods[reverseSignature] = Method(reverseSignature, "${fromClass.to}/${tokens[3]} ${toSignature(tokens[2])}")
            }
        }
    }
}

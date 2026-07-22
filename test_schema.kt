import com.google.firebase.vertexai.type.Schema
import com.google.firebase.vertexai.type.Type
fun test() {
    val s = Schema(
        name = "test",
        type = Type.OBJECT,
        properties = mapOf("foo" to Schema(type = Type.STRING, name = "foo"))
    )
}

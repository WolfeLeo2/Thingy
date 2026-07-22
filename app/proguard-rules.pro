# Firestore deserializes these model classes reflectively (toObject/toObjects) — keep the class
# names, fields, getters, and the synthesized no-arg constructor, or reads come back null/blank.
-keep class com.wolfeleo2.thingy.data.Item { *; }
-keep class com.wolfeleo2.thingy.data.Space { *; }
-keep class com.wolfeleo2.thingy.data.SpaceItem { *; }
-keep class com.wolfeleo2.thingy.data.Intent { *; }
-keep class com.wolfeleo2.thingy.data.Product { *; }

# Firestore annotations on the models (@DocumentId / @ServerTimestamp) must survive.
-keepattributes *Annotation*,Signature

# Most SDKs ship consumer R8 rules; silence warnings for optional/absent classes they reference.
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**

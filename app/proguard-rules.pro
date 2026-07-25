# Firestore maps these model classes reflectively, both ways: toObject() needs the synthesized
# no-arg constructor, and set() derives document field keys from getter/field *names* — obfuscate
# either and reads come back blank while writes land under garbage keys.
#
# Package-wide on purpose. This was an explicit per-class list and it rotted: SpaceMember/SpaceInvite
# were added for shared spaces, nobody updated the list, and release builds crashed on the first
# members read. keepclassmembers (not keep) only applies to classes R8 already retains, so unused
# classes still get stripped — the rule can't rot but doesn't bloat the APK either.
-keepclassmembers class com.wolfeleo2.thingy.data.** {
    <init>();
    <fields>;
    *** get*();
    void set*(***);
}

# Firestore annotations on the models (@DocumentId / @ServerTimestamp) must survive.
-keepattributes *Annotation*,Signature

# Most SDKs ship consumer R8 rules; silence warnings for optional/absent classes they reference.
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**

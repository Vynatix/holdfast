# Holdfast — consumer ProGuard/R8 rules.
#
# What these rules protect: on JVM/Android, `Store<Self : Store<Self>>`
# subclasses are verified AT CONSTRUCTION by walking the generic superclass
# chain with java.lang.reflect (`getGenericSuperclass` + level-by-level type
# variable substitution) and checking that the resolved Self argument can hold
# the store (`rawSelf.isAssignableFrom(javaClass)` — the soundness condition of
# the `this as Self` receiver cast). A mistyped store (`class Foo :
# Store<Bar>()`) fails with a teaching IllegalStateException naming both types.
#
# Why keepattributes: R8 strips the `Signature` class-file attribute by
# default, which degrades every `getGenericSuperclass()` to a raw class and
# disables the walk. The check always FAILS OPEN — a minified app without
# these rules never breaks, it merely loses the construction-time teaching
# failure for mistyped stores (they fail later, at first dispatch).
-keepattributes Signature

# The two bases in the runtime-inspected superclass chain. Apps keep their own
# store subclasses (they instantiate them); pinning the bases keeps R8's
# class-merging from distorting the chain the walk inspects.
-keep class com.vynatix.holdfast.Store { *; }
-keep class com.vynatix.holdfast.EventfulStore { *; }

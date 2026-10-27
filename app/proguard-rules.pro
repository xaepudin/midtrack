# Midtrack has no reflection, but readable release stack traces make bug
# reports useful. R8 still obfuscates class and member names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

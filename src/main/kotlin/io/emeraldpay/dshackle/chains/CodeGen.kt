

class CodeGen {

//    fun generateFromChains(path: File) {
//        val fileResolver = FileResolver(File("."))
//        val upstremConfigReader = Upstream(fileResolver)
//        val chainConfigReader = ChainsConfigReader(upstremConfigReader)
//        val config = chainConfigReader.read(null)
//        CodeGen(config).generateChainsFile().writeTo(path)
//    }
//
//    private fun addEnumProperties(builder: TypeSpec.Builder): TypeSpec.Builder {
//        builder.addEnumConstant(
//            "UNSPECIFIED",
//            TypeSpec.anonymousClassBuilder()
//                .addSuperclassConstructorParameter("%L, %S, %S, %L", 0, "UNSPECIFIED", "Unknown", 0L)
//                .build(),
//        )
//        for (chain in config) {
//            builder.addEnumConstant(
//                chain.blockchain.uppercase() + "__" + chain.id.uppercase(),
//                TypeSpec.anonymousClassBuilder()
//                    .addSuperclassConstructorParameter(
//                        "%L, %S, %S",
//                        chain.grpcId,
//                        chain.code,
//                        chain.blockchain.replaceFirstChar { it.uppercase() } + " " + { chain.id.replaceFirstChar { it.uppercase() } },
//                    )
//                    .build(),
//            )
//        }
//        return builder
//    }
//
//    fun generateChainsFile(): FileSpec {
//        val chainType = addEnumProperties(
//            TypeSpec.enumBuilder("Chain")
//                .primaryConstructor(
//                    FunSpec.constructorBuilder()
//                        .addParameter("id", Int::class)
//                        .addParameter("chainCode", String::class)
//                        .addParameter("chainName", String::class)
//                        .build(),
//                )
//                .addProperty(
//                    PropertySpec.builder("id", Int::class)
//                        .initializer("id")
//                        .build(),
//                )
//                .addProperty(
//                    PropertySpec.builder("chainCode", String::class)
//                        .initializer("chainCode")
//                        .build(),
//                )
//                .addProperty(
//                    PropertySpec.builder("chainName", String::class)
//                        .initializer("chainName")
//                        .build(),
//                ),
//        ).build()
//        return FileSpec.builder(this::class.java.`package`.name, "Chain")
//            .addType(chainType)
//            .build()
//    }
}

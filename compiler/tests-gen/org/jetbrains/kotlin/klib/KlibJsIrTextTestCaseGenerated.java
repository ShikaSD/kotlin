/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.klib;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.test.generators.GenerateCompilerTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("compiler/testData/ir/irText/js")
@TestDataPath("$PROJECT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public class KlibJsIrTextTestCaseGenerated extends AbstractKlibJsIrTextTestCase {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, TargetBackend.JS_IR, testDataFilePath);
    }

    public void testAllFilesPresentInJs() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler/testData/ir/irText/js"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JS_IR, true);
    }

    @TestMetadata("compiler/testData/ir/irText/js/dynamic")
    @TestDataPath("$PROJECT_ROOT")
    @RunWith(JUnit3RunnerWithInners.class)
    public static class Dynamic extends AbstractKlibJsIrTextTestCase {
        private void runTest(String testDataFilePath) throws Exception {
            KotlinTestUtils.runTest(this::doTest, TargetBackend.JS_IR, testDataFilePath);
        }

        public void testAllFilesPresentInDynamic() throws Exception {
            KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler/testData/ir/irText/js/dynamic"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JS_IR, true);
        }

        @TestMetadata("dynamicAndMembersOfAny.kt")
        public void testDynamicAndMembersOfAny() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicAndMembersOfAny.kt");
        }

        @TestMetadata("dynamicArrayAccess.kt")
        public void testDynamicArrayAccess() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicArrayAccess.kt");
        }

        @TestMetadata("dynamicArrayAssignment.kt")
        public void testDynamicArrayAssignment() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicArrayAssignment.kt");
        }

        @TestMetadata("dynamicArrayAugmentedAssignment.kt")
        public void testDynamicArrayAugmentedAssignment() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicArrayAugmentedAssignment.kt");
        }

        @TestMetadata("dynamicArrayIncrementDecrement.kt")
        public void testDynamicArrayIncrementDecrement() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicArrayIncrementDecrement.kt");
        }

        @TestMetadata("dynamicBinaryEqualityOperator.kt")
        public void testDynamicBinaryEqualityOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicBinaryEqualityOperator.kt");
        }

        @TestMetadata("dynamicBinaryLogicalOperator.kt")
        public void testDynamicBinaryLogicalOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicBinaryLogicalOperator.kt");
        }

        @TestMetadata("dynamicBinaryOperator.kt")
        public void testDynamicBinaryOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicBinaryOperator.kt");
        }

        @TestMetadata("dynamicBinaryRelationalOperator.kt")
        public void testDynamicBinaryRelationalOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicBinaryRelationalOperator.kt");
        }

        @TestMetadata("dynamicCall.kt")
        public void testDynamicCall() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicCall.kt");
        }

        @TestMetadata("dynamicElvisOperator.kt")
        public void testDynamicElvisOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicElvisOperator.kt");
        }

        @TestMetadata("dynamicExclExclOperator.kt")
        public void testDynamicExclExclOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicExclExclOperator.kt");
        }

        @TestMetadata("dynamicInfixCall.kt")
        public void testDynamicInfixCall() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicInfixCall.kt");
        }

        @TestMetadata("dynamicMemberAccess.kt")
        public void testDynamicMemberAccess() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicMemberAccess.kt");
        }

        @TestMetadata("dynamicMemberAssignment.kt")
        public void testDynamicMemberAssignment() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicMemberAssignment.kt");
        }

        @TestMetadata("dynamicMemberAugmentedAssignment.kt")
        public void testDynamicMemberAugmentedAssignment() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicMemberAugmentedAssignment.kt");
        }

        @TestMetadata("dynamicMemberIncrementDecrement.kt")
        public void testDynamicMemberIncrementDecrement() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicMemberIncrementDecrement.kt");
        }

        @TestMetadata("dynamicUnaryOperator.kt")
        public void testDynamicUnaryOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicUnaryOperator.kt");
        }

        @TestMetadata("dynamicWithSmartCast.kt")
        public void testDynamicWithSmartCast() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/dynamicWithSmartCast.kt");
        }

        @TestMetadata("implicitCastFromDynamic.kt")
        public void testImplicitCastFromDynamic() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/implicitCastFromDynamic.kt");
        }

        @TestMetadata("implicitCastToDynamic.kt")
        public void testImplicitCastToDynamic() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/implicitCastToDynamic.kt");
        }

        @TestMetadata("invokeOperator.kt")
        public void testInvokeOperator() throws Exception {
            runTest("compiler/testData/ir/irText/js/dynamic/invokeOperator.kt");
        }
    }

    @TestMetadata("compiler/testData/ir/irText/js/external")
    @TestDataPath("$PROJECT_ROOT")
    @RunWith(JUnit3RunnerWithInners.class)
    public static class External extends AbstractKlibJsIrTextTestCase {
        private void runTest(String testDataFilePath) throws Exception {
            KotlinTestUtils.runTest(this::doTest, TargetBackend.JS_IR, testDataFilePath);
        }

        public void testAllFilesPresentInExternal() throws Exception {
            KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler/testData/ir/irText/js/external"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JS_IR, true);
        }

        @TestMetadata("kt38765.kt")
        public void testKt38765() throws Exception {
            runTest("compiler/testData/ir/irText/js/external/kt38765.kt");
        }
    }

    @TestMetadata("compiler/testData/ir/irText/js/native")
    @TestDataPath("$PROJECT_ROOT")
    @RunWith(JUnit3RunnerWithInners.class)
    public static class Native extends AbstractKlibJsIrTextTestCase {
        private void runTest(String testDataFilePath) throws Exception {
            KotlinTestUtils.runTest(this::doTest, TargetBackend.JS_IR, testDataFilePath);
        }

        public void testAllFilesPresentInNative() throws Exception {
            KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler/testData/ir/irText/js/native"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JS_IR, true);
        }

        @TestMetadata("nativeNativeKotlin.kt")
        public void testNativeNativeKotlin() throws Exception {
            runTest("compiler/testData/ir/irText/js/native/nativeNativeKotlin.kt");
        }
    }
}

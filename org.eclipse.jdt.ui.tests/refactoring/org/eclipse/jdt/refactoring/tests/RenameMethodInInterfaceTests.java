/*
 * Licensed Materials - Property of IBM,
 * WebSphere Studio Workbench
 * (c) Copyright IBM Corp 1999, 2000
 */
package org.eclipse.jdt.refactoring.tests;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.refactoring.ChangeContext;import org.eclipse.jdt.core.refactoring.IRefactoring;
import org.eclipse.jdt.core.refactoring.Refactoring;
import org.eclipse.jdt.core.refactoring.RefactoringStatus;
import org.eclipse.jdt.core.refactoring.methods.RenameMethodInInterfaceRefactoring;

import org.eclipse.jdt.refactoring.tests.infra.TestExceptionHandler;import org.eclipse.jdt.testplugin.JavaTestSetup;
import org.eclipse.jdt.testplugin.TestPluginLauncher;
import org.eclipse.jdt.testplugin.ui.*;

public class RenameMethodInInterfaceTests extends RefactoringTest {

	private static final String REFACTORING_PATH= "RenameMethodInInterface/";

	public RenameMethodInInterfaceTests(String name) {
		super(name);
	}
	
	public static void main(String[] args) {
		TestPluginUILauncher.run(TestPluginLauncher.getLocationFromProperties(), RenameMethodInInterfaceTests.class, args);
	}
	
	public static Test suite() {
		TestSuite suite= new TestSuite();
		suite.addTest(noSetupSuite());
		return new JavaTestSetup(suite);
	}

	public static Test noSetupSuite() {
		return new TestSuite(RenameMethodInInterfaceTests.class);
	}

	protected String getRefactoringPath() {
		return REFACTORING_PATH;
	}

	private void helper1_0(String methodName, String newMethodName, String[] signatures) throws Exception{
		ICompilationUnit cu= createCUfromTestFile(getPackageP(), "A");
		IType interfaceI= getType(cu, "I");
		IRefactoring ref= new RenameMethodInInterfaceRefactoring(fgChangeCreator, getScope(), interfaceI.getMethod(methodName, signatures), newMethodName);
		RefactoringStatus result= performRefactoring(ref);
		assertNotNull("precondition was supposed to fail", result);
	}
	
	private void helper1() throws Exception{
		helper1_0("m", "k", new String[0]);
	}
	
	private void helper2_0(String methodName, String newMethodName, String[] signatures, boolean shouldPass) throws Exception{
		ICompilationUnit cu= createCUfromTestFile(getPackageP(), "A");
		IType interfaceI= getType(cu, "I");
		IRefactoring ref= new RenameMethodInInterfaceRefactoring(fgChangeCreator, getScope(), interfaceI.getMethod(methodName, signatures), newMethodName);
		assertEquals("was supposed to pass", null, performRefactoring(ref));
		if (!shouldPass){
			assert("incorrect renaming because of a java model bug", ! getFileContents(getOutputTestFileName("A")).equals(cu.getSource()));
			return;
		}
		assertEquals("incorrect renaming", getFileContents(getOutputTestFileName("A")), cu.getSource());
		
		assert("anythingToUndo", Refactoring.getUndoManager().anythingToUndo());
		assert("! anythingToRedo", !Refactoring.getUndoManager().anythingToRedo());
		//assertEquals("1 to undo", 1, Refactoring.getUndoManager().getRefactoringLog().size());
		
		Refactoring.getUndoManager().performUndo(new ChangeContext(new TestExceptionHandler()), new NullProgressMonitor());
		assertEquals("invalid undo", getFileContents(getInputTestFileName("A")), cu.getSource());

		assert("! anythingToUndo", !Refactoring.getUndoManager().anythingToUndo());
		assert("anythingToRedo", Refactoring.getUndoManager().anythingToRedo());
		//assertEquals("1 to redo", 1, Refactoring.getUndoManager().getRedoStack().size());
		
		Refactoring.getUndoManager().performRedo(new ChangeContext(new TestExceptionHandler()), new NullProgressMonitor());
		assertEquals("invalid redo", getFileContents(getOutputTestFileName("A")), cu.getSource());
	}
	
	private void helper2_0(String methodName, String newMethodName, String[] signatures) throws Exception{
		helper2_0(methodName, newMethodName, signatures, true);
	}
	
	private void helper2() throws Exception{
		helper2_0("m", "k", new String[0]);
	}
	
	private void helper2_fail() throws Exception{
		helper2_0("m", "k", new String[0], false);
	}
	

	/********tests************/
	public void testFail0() throws Exception{
		helper1();
	}
	public void testFail1() throws Exception{
		helper1();
	}
	public void testFail3() throws Exception{
		helper1();
	}
	public void testFail4() throws Exception{
		helper1();
	}
	public void testFail5() throws Exception{
		helper1();
	}
	public void testFail6() throws Exception{
		helper1();
	}
	public void testFail7() throws Exception{
		helper1();
	}
	public void testFail8() throws Exception{
		helper1_0("m", "k", new String[]{Signature.SIG_INT});
	}
	public void testFail9() throws Exception{
		helper1();
	}
	public void testFail10() throws Exception{
		helper1();
	}
	public void testFail11() throws Exception{
		helper1();
	}
	public void testFail12() throws Exception{
		helper1();
	}
	public void testFail13() throws Exception{
		helper1();
	}
	public void testFail14() throws Exception{
		helper1();
	}
	public void testFail15() throws Exception{
		helper1();
	}
	public void testFail16() throws Exception{
		helper1();
	}
	public void testFail17() throws Exception{
		helper1();
	}
	public void testFail18() throws Exception{
		helper1();
	}
	public void testFail19() throws Exception{
		helper1();
	}
	public void testFail20() throws Exception{
		helper1();
	}
	public void testFail21() throws Exception{
		helper1_0("m", "k", new String[]{"QString;"});
	}
	public void testFail22() throws Exception{
		helper1_0("m", "k", new String[]{"QObject;"});
	}
	public void testFail23() throws Exception{
		helper1_0("toString", "k", new String[0]);
	}
	public void testFail24() throws Exception{
		helper1();
	}
	public void testFail25() throws Exception{
		helper1();
	}
	public void testFail26() throws Exception{
		helper1();
	}
	public void testFail27() throws Exception{
		helper1();
	}
	public void testFail28() throws Exception{
		helper1();
	}
	public void testFail29() throws Exception{
		helper1();
	}
	
	public void testFail30() throws Exception{
		helper1_0("toString", "k", new String[0]);
	}
	
	public void testFail31() throws Exception{
		helper1_0("toString", "k", new String[0]);
	}
	
	public void testFail32() throws Exception{
		helper1_0("m", "toString", new String[0]);
	}
	
	public void testFail33() throws Exception{
		helper1_0("m", "toString", new String[0]);
	}
	
	public void testFail34() throws Exception{
		helper1_0("m", "equals", new String[]{"QObject;"});
	}
	
	public void testFail35() throws Exception{
		helper1_0("m", "equals", new String[]{"Qjava.lang.Object;"});
	}
	
	public void testFail36() throws Exception{
		helper1_0("m", "getClass", new String[0]);
	}
	
	public void testFail37() throws Exception{
		helper1_0("m", "hashCode", new String[0]);
	}

	public void testFail38() throws Exception{
		helper1_0("m", "notify", new String[0]);
	}	

	public void testFail39() throws Exception{
		helper1_0("m", "notifyAll", new String[0]);
	}	
	
	public void testFail40() throws Exception{
		helper1_0("m", "wait", new String[]{Signature.SIG_LONG, Signature.SIG_INT});
	}	

	public void testFail41() throws Exception{
		helper1_0("m", "wait", new String[]{Signature.SIG_LONG});
	}	
	
	public void testFail42() throws Exception{
		helper1_0("m", "wait", new String[0]);
	}	
	
	public void testFail43() throws Exception{
		helper1_0("m", "wait", new String[0]);
	}	
	
	
	public void test0() throws Exception{
		helper2();
	}
	public void test1() throws Exception{
		helper2();
	}
	public void test2() throws Exception{
		helper2();
	}
	public void test3() throws Exception{
		helper2();
	}
	public void test4() throws Exception{
		helper2();
	}
	public void test5() throws Exception{
		helper2();
	}
	public void test6() throws Exception{
		helper2();
	}
	public void test7() throws Exception{
		helper2();
	}
	public void test10() throws Exception{
		helper2();
	}
	public void test11() throws Exception{
		helper2();
	}	
	public void test12() throws Exception{
		helper2();
	}
	public void test13() throws Exception{
		helper2();
	}
	public void test14() throws Exception{
		helper2();
	}
	public void test15() throws Exception{
		helper2();
	}
	public void test16() throws Exception{
		helper2();
	}
	public void test17() throws Exception{
		helper2();
	}
	public void test18() throws Exception{
		helper2();
	}
	public void test19() throws Exception{
		helper2();
	}
	public void test20() throws Exception{
		helper2();
	}
	//anonymous inner class
	public void test21() throws Exception{
		helper2_fail();
	}
	public void test22() throws Exception{
		helper2();
	}
	public void test23() throws Exception{
		helper2();
	}
	public void test24() throws Exception{
		helper2();
	}
	public void test25() throws Exception{
		helper2();
	}
	public void test26() throws Exception{
		helper2();
	}
	public void test27() throws Exception{
		helper2();
	}
	public void test28() throws Exception{
		helper2();
	}
	public void test29() throws Exception{
		helper2();
	}
	public void test30() throws Exception{
		helper2();
	}
	//anonymous inner class
	public void test31() throws Exception{
		helper2_fail();
	}
	//anonymous inner class
	public void test32() throws Exception{
		helper2_fail();
	}
	public void test33() throws Exception{
		helper2();
	}
	public void test34() throws Exception{
		helper2();
	}
	public void test35() throws Exception{
		helper2();
	}
	public void test36() throws Exception{
		helper2();
	}
	public void test37() throws Exception{
		helper2();
	}
	public void test38() throws Exception{
		helper2();
	}
	public void test39() throws Exception{
		helper2();
	}
	public void test40() throws Exception{
		helper2();
	}
	public void test41() throws Exception{
		helper2();
	}
	public void test42() throws Exception{
		helper2();
	}
	public void test43() throws Exception{
		helper2();
	}
	public void test44() throws Exception{
		helper2();
	}
	
}

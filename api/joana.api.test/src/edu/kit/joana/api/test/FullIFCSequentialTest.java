/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.api.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Collection;

import org.junit.Test;

import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;

import edu.kit.joana.api.IFCAnalysis;
import edu.kit.joana.api.IllicitFlow;
import edu.kit.joana.api.sdg.SDGConfig;
import edu.kit.joana.api.sdg.SDGProgram;
import edu.kit.joana.api.sdg.SDGProgramPart;
import edu.kit.joana.ifc.sdg.util.JavaMethodSignature;
import edu.kit.joana.wala.core.SDGBuilder.ExceptionAnalysis;
import edu.kit.joana.wala.core.SDGBuilder.FieldPropagation;
import edu.kit.joana.wala.core.SDGBuilder.PointsToPrecision;

/**
 * @author Juergen Graf <graf@kit.edu>
 */
public class FullIFCSequentialTest {

	public static final String CLASSPATH = "../../example/joana.example.many-small-progs/bin";
	public static final String STUBS = "../../contrib/lib/stubs/jSDG-stubs-jre1.4.jar";

	public static IFCAnalysis buildAndAnnotate(final String className) throws ApiTestException {
		return buildAndAnnotate(className, PointsToPrecision.CONTEXT_SENSITIVE);
	}
	
	public static IFCAnalysis buildAndAnnotate(final String className, PointsToPrecision pts) throws ApiTestException {
		JavaMethodSignature mainMethod = JavaMethodSignature.mainMethodOfClass(className);
		SDGConfig config = new SDGConfig(CLASSPATH, mainMethod.toBCString(), STUBS);
		config.setComputeInterferences(false);
		config.setExceptionAnalysis(ExceptionAnalysis.INTRAPROC);
		config.setFieldPropagation(FieldPropagation.OBJ_GRAPH);
		config.setPointsToPrecision(pts);
		SDGProgram prog = null;
		
		try {
			prog = SDGProgram.createSDGProgram(config);
		} catch (ClassHierarchyException e) {
			throw new ApiTestException(e);
		} catch (IOException e) {
			throw new ApiTestException(e);
		} catch (UnsoundGraphException e) {
			throw new ApiTestException(e);
		} catch (CancelException e) {
			throw new ApiTestException(e);
		}
		
		IFCAnalysis ana = new IFCAnalysis(prog);
		SDGProgramPart secret = ana.getProgramPart("sequential.Security.SECRET");
		assertNotNull(secret);
		ana.addSourceAnnotation(secret, IFCAnalysis.STD_LATTICE_HIGH);
		SDGProgramPart output = ana.getProgramPart("sequential.Security.PUBLIC");
		assertNotNull(output);
		ana.addSinkAnnotation(output, IFCAnalysis.STD_LATTICE_LOW);
		
		return ana;
	}
	
	@Test
	public void testPraktomatValid() {
		try {
			IFCAnalysis ana = buildAndAnnotate("sequential.PraktomatValid");
			Collection<IllicitFlow> illegal = ana.doIFC();
			assertTrue(illegal.isEmpty());
			assertEquals(0, illegal.size());
		} catch (ApiTestException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testPraktomatLeak() {
		try {
			IFCAnalysis ana = buildAndAnnotate("sequential.PraktomatLeak");
			Collection<IllicitFlow> illegal = ana.doIFC();
			assertFalse(illegal.isEmpty());
			assertEquals(4, illegal.size());
		} catch (ApiTestException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

}
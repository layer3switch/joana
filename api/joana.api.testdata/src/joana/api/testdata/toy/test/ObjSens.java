/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package joana.api.testdata.toy.test;

class IntPrinter {
	int val;

	public IntPrinter(int val) {
		this.val = val;
	}

	public void print() {
		System.out.println(val);
	}
}


/**
 * This example demonstrates that our analysis is object-sensitive. Two instances
 * of IntPrinter are created - one is initialized with a secret value, the other
 * with a non-secret value. Then, for both instances the print() method is called.
 * If System.out is considered as public sink, then one illegal flow is detected
 * for the instance initialized with the secret value. The other instance does
 * *not* produce an illegal flow, since it was initialized with a non-critical
 * value.
 * @author Martin Mohr
 *
 */
public class ObjSens {

	private int sec = 42;
	private int pub = 17;

	public void run() {
		IntPrinter p1 = new IntPrinter(pub);
		IntPrinter p2 = new IntPrinter(sec);
		p1.print(); // this is okay
		p2.print(); // this leaks the secret value to System.out
	}

	public static void main(String[] args) {
		new ObjSens().run();
	}
}

package whilelang.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jx86.lang.*;
import whilelang.lang.*;
import whilelang.util.*;

public class X86FileWriter {

	// ==========================================
	// Fields
	// ==========================================

	private final Target target;
	private HashMap<String, WhileFile.FunDecl> functions;
	private HashMap<String,WhileFile.TypeDecl> types;
	
	// ==========================================
	// Constructors
	// ==========================================

	public X86FileWriter(Target target) {
		this.target = target;

		// Initialise register heads --- the largest register available in a
		// given family for the target platform.
		HAX = headOfFamily(Register.AX);
		HBX = headOfFamily(Register.BX);
		HCX = headOfFamily(Register.CX);
		HDX = headOfFamily(Register.DX);
		HDI = headOfFamily(Register.DI);
		HSI = headOfFamily(Register.SI);
		HBP = headOfFamily(Register.BP);
		HSP = headOfFamily(Register.SP);
		HIP = headOfFamily(Register.IP);

		// Initialise the default register pool
		REGISTER_POOL = new ArrayList<Register>();
		REGISTER_POOL.add(HBX);
		REGISTER_POOL.add(HCX);
		REGISTER_POOL.add(HDX);
		REGISTER_POOL.add(HDI);
		REGISTER_POOL.add(HSI);
	}

	// ==========================================
	// Public Build Method
	// ==========================================

	public X86File build(WhileFile wf) {
		X86File.Code code = new X86File.Code();
		X86File.Data data = new X86File.Data();

		this.functions = new HashMap<String, WhileFile.FunDecl>();
		this.types = new HashMap<String,WhileFile.TypeDecl>();
		
		for (WhileFile.Decl declaration : wf.declarations) {
			if (declaration instanceof WhileFile.FunDecl) {
				WhileFile.FunDecl fd = (WhileFile.FunDecl) declaration;
				this.functions.put(fd.name(), fd);
			} else if(declaration instanceof WhileFile.TypeDecl) {
				WhileFile.TypeDecl fd = (WhileFile.TypeDecl) declaration;
				this.types.put(fd.name(), fd);
			}
		}

		for (WhileFile.Decl d : wf.declarations) {
			if (d instanceof WhileFile.FunDecl) {
				translate((WhileFile.FunDecl) d, code, data);
			}
		}

		addMainLauncher(code);

		return new X86File(code, data);
	}

	// ==========================================
	// Build Helpers
	// ==========================================

	/**
	 * Translate a given function declaration into a sequence of assembly
	 * language instructions.
	 * 
	 * @param fd
	 *            Function Declaration to translate.
	 * @param code
	 *            x86 code section where translation should be added.
	 */
	public void translate(WhileFile.FunDecl fd, X86File.Code code,
			X86File.Data data) {
		List<Instruction> instructions = code.instructions;

		// NOTE: prefix name with "wl_" to avoid potential name clashes.
		instructions.add(new Instruction.Label("wl_" + fd.name));

		// Save the old frame pointer
		instructions.add(new Instruction.Reg(Instruction.RegOp.push, HBP));

		// Create new frame pointer for this function
		instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov, HSP,
				HBP));

		// Create stack frame and ensure every variable has a known position on
		// the stack. Parameters are passed on the stack by the caller.
		HashMap<String, Integer> localVariables = new HashMap<String, Integer>();
		int widthOfLocals = allocateStackFrame(fd, localVariables);
		
		// Create the label for return statements. This is the point where
		// return statements will branch to, so we can avoid repeating the code
		// necessary for restoring the stack.
		int exitLabel = labelIndex++;
		localVariables.put("$$", exitLabel); // sneaky
		
		// Create space for the stack frame, which consists of the local
		// variables.
		instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.sub,
				widthOfLocals, HSP));

		// translate the statements
		translate(fd.statements, localVariables, code, data);

		// Add the return label
		instructions.add(new Instruction.Label("label"+exitLabel));
		
		// Restore stack pointer
		instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov, HBP,
				HSP));

		// Restore old frame pointer
		instructions.add(new Instruction.Reg(Instruction.RegOp.pop, HBP));

		// Return from procedure
		instructions.add(new Instruction.Unit(Instruction.UnitOp.ret));
	}

	/**
	 * Translate a list of While statements into their corresponding machine
	 * code instructions. Observe that we implicitly assume all registers are
	 * available for use between statements.
	 * 
	 * @param statements
	 * @param code
	 */
	public void translate(List<Stmt> statements,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		for (Stmt statement : statements) {
			translate(statement, localVariables, code, data);
		}
	}

	/**
	 * Translate a given While statement into its corresponding corresponding
	 * machine code instructions. Observe that we implicitly assume all
	 * registers are available for use between statements.
	 * 
	 * @param statement
	 *            Statement to be translated
	 * @param code
	 *            X86 code section to write machine code instructions to.
	 */
	public void translate(Stmt statement, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		if (statement instanceof Stmt.Assign) {
			translate((Stmt.Assign) statement, localVariables, code, data);
		} else if (statement instanceof Stmt.For) {
			translate((Stmt.For) statement, localVariables, code, data);
		} else if (statement instanceof Stmt.IfElse) {
			translate((Stmt.IfElse) statement, localVariables, code, data);
		} else if (statement instanceof Expr.Invoke) {
			// We assign result of invoke to HDI, but this is just discarded.
			translate((Expr.Invoke) statement, HDI, new ArrayList<Register>(
					REGISTER_POOL), localVariables, code, data);
		} else if (statement instanceof Stmt.Print) {
			translate((Stmt.Print) statement, localVariables, code, data);
		} else if (statement instanceof Stmt.Return) {
			translate((Stmt.Return) statement, localVariables, code, data);
		} else if (statement instanceof Stmt.VariableDeclaration) {
			translate((Stmt.VariableDeclaration) statement, localVariables,
					code, data);
		} else if (statement instanceof Stmt.While) {
			translate((Stmt.While) statement, localVariables, code, data);
		} else {
			throw new IllegalArgumentException(
					"Unknown statement encountered: " + statement);
		}
	}

	public void translate(Stmt.Assign statement,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		Expr lhs = statement.getLhs();

		// Translate the right-hand side and load result into HDI register
		translate(statement.getRhs(), HDI, new ArrayList<Register>(
				REGISTER_POOL), localVariables, code, data);

		// Translate assignment from HDI to left-hand side
		if (lhs instanceof Expr.Variable) {
			Expr.Variable v = (Expr.Variable) lhs;
			
			// Determine the offset within the stack of this local variable.
			int offset = localVariables.get(v.getName());

			// Extract the variable's type to help determine which case to
			// handle
			Type type = v.attribute(Attribute.Type.class).type;

			// Implement the assignment
			writeToStack(type,HDI,HBP,offset,code,data);
			
		} else if (lhs instanceof Expr.RecordAccess) {
			Expr.RecordAccess v = (Expr.RecordAccess) lhs;
			
			// First, determine the field offset
			Type.Record type = (Type.Record) unwrap(v.getSource().attribute(Attribute.Type.class).type);
			int offset = determineFieldOffset(type,v.getName());
		
			// Translate source expression to give pointer to structure.
			translate(v.getSource(), HSI, new ArrayList<Register>(
					REGISTER_POOL), localVariables, code, data);

			// Finally, perform indirect write
			instructions.add(new Instruction.RegImmInd(
					Instruction.RegImmIndOp.mov, HDI, offset, HSI));
			
		} else if (lhs instanceof Expr.IndexOf) {
			Expr.IndexOf v = (Expr.IndexOf) lhs;
		}
	}

	public void translate(Stmt.For statement,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		String headLabel = freshLabel(); 
		String exitLabel = freshLabel();
		
		// 1. Translate Variable Declaration
		translate(statement.getDeclaration(), localVariables, code, data);

		// 2. Start loop, and translate condition
		instructions.add(new Instruction.Label(headLabel));
		translate(statement.getCondition(), HDI, new ArrayList<Register>(
				REGISTER_POOL), localVariables, code, data);
		instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.cmp, 0,
				HDI));
		instructions.add(new Instruction.Addr(Instruction.AddrOp.jz, exitLabel));

		// 3. Translate Loop Body
		translate(statement.getBody(), localVariables, code, data);
		
		// 4. Translate Increment and loop around
		translate(statement.getIncrement(), localVariables, code, data);
		instructions.add(new Instruction.Addr(Instruction.AddrOp.jmp, headLabel));

		// 5. Exit ...
		instructions.add(new Instruction.Label(exitLabel));
	}

	public void translate(Stmt.IfElse statement,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		
	}

	public void translate(Stmt.Print statement,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		List<Instruction> instructions = code.instructions;

		// Translate the target expression and load result into HDI register
		translate(statement.getExpr(), HDI, new ArrayList<Register>(
				REGISTER_POOL), localVariables, code, data);

		// Determine type of expression so as to determine appropriate print
		// call.
//		Type type = statement.getExpr().attribute(Attribute.Type.class).type;// .attribute returns null
        Type type = new Type.Strung();        // hack!
				
		String typeLabel = freshLabel();
		addTypeConstant(type,typeLabel,data);
		
		instructions.add(new Instruction.AddrRegReg(Instruction.AddrRegRegOp.lea,
				typeLabel, HIP, HSI));

		instructions
				.add(new Instruction.Addr(Instruction.AddrOp.call, "_print"));  // fixme
	}

	public void translate(Stmt.Return statement,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		Expr rv = statement.getExpr();

		if (rv != null) {
			// Translate the right-hand side and load result into HDI register
			translate(rv, HDI, new ArrayList<Register>(REGISTER_POOL),
					localVariables, code, data);

			// Determine the offset within the stack of this local variable.
			int offset = localVariables.get("$");

			// Extract the variable's type to help determine which case to
			// handle
			Type type = rv.attribute(Attribute.Type.class).type;

			// Implement the assignment
			writeToStack(type, HDI, HBP, offset, code, data);
		}

		// Finally, we branch to the end of the function where the code
		// necessary for restoring the stack is located.
		int exitLabel = localVariables.get("$$"); // sneaky ;)
		instructions.add(new Instruction.Addr(Instruction.AddrOp.jmp, "label"
				+ exitLabel));
	}

	public void translate(Stmt.VariableDeclaration statement,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		Expr initialiser = statement.getExpr();

		if (initialiser != null) {

			// Translate the right-hand side and load result into HDI register
			translate(initialiser, HDI, new ArrayList<Register>(REGISTER_POOL),
					localVariables, code, data);

			// Determine the offset within the stack of this local variable.
			int offset = localVariables.get(statement.getName());

			// Extract the variable's type to help determine which case to
			// handle
			Type type = statement.getType();

			// Implement the assignment
			writeToStack(type,HDI,HBP,offset,code,data);
		}
	}

	public void translate(Stmt.While statement,
			Map<String, Integer> localVariables, X86File.Code code,
			X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		String headLabel = freshLabel(); 
		String exitLabel = freshLabel();
		
		instructions.add(new Instruction.Label(headLabel));
		
		// Translate the condition expression and load result into HDI register
		translate(statement.getCondition(), HDI, new ArrayList<Register>(
				REGISTER_POOL), localVariables, code, data);
		instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.cmp, 0,
				HDI));
		instructions.add(new Instruction.Addr(Instruction.AddrOp.jz, exitLabel));

		// Translate Loop Body
		translate(statement.getBody(), localVariables, code, data);
		instructions.add(new Instruction.Addr(Instruction.AddrOp.jmp, headLabel));

		// Loop exit..
		instructions.add(new Instruction.Label(exitLabel));
	}

	/**
	 * Translate a given expression into the corresponding machine code
	 * instructions. The expression is expected to return its result in the
	 * target register or, if that is null, on the stack. The set of free
	 * registers is provided to identify the pool from which target registers
	 * can be taken.
	 * 
	 * @param expression
	 *            Expression to be translated.
	 * @param target
	 *            Register to store result in; if this is <code>null</code> then
	 *            result stored on stack
	 * @param freeRegisters
	 *            Set of available registers at this point
	 * @param localVariables
	 *            Mapping of local variable names to their byte offset from the
	 *            frame pointer.
	 * @param code
	 *            Code section to add on those instructions corresponding to
	 *            this expression
	 * @param data
	 *            Data section to store any constants required by instructions
	 *            generated for this expression (e.g. string constants)
	 */
	public void translate(Expr expression, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		if (expression instanceof Expr.Binary) {
			translate((Expr.Binary) expression, target, freeRegisters,
					localVariables, code, data);
		} else if (expression instanceof Expr.Constant) {
			translate((Expr.Constant) expression, target, freeRegisters,
					localVariables, code, data);
		} else if (expression instanceof Expr.Cast) {
			translate((Expr.Cast) expression, target, freeRegisters,
					localVariables, code, data);
		} else if (expression instanceof Expr.IndexOf) {
			translate((Expr.IndexOf) expression, target, freeRegisters,
					localVariables, code, data);
		} else if (expression instanceof Expr.Invoke) {
			translate((Expr.Invoke) expression, target, freeRegisters,
					localVariables, code, data);
		} else if (expression instanceof Expr.ListConstructor) {
			translate((Expr.ListConstructor) expression, target, freeRegisters,
					localVariables, code, data);
		} else if (expression instanceof Expr.RecordAccess) {
			translate((Expr.RecordAccess) expression, target,
					freeRegisters, localVariables, code, data);
		} else if (expression instanceof Expr.RecordConstructor) {
			translate((Expr.RecordConstructor) expression, target,
					freeRegisters, localVariables, code, data);
		} else if (expression instanceof Expr.Unary) {
			translate((Expr.Unary) expression, target, freeRegisters,
					localVariables, code, data);
		} else if (expression instanceof Expr.Variable) {
			translate((Expr.Variable) expression, target, freeRegisters,
					localVariables, code, data);
		} else {
			throw new IllegalArgumentException(
					"Unknown expression encountered: " + expression);
		}
	}

	public void translate(Expr.Binary e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		Type lhsType = e.getLhs().attribute(Attribute.Type.class).type;
		Type rhsType = e.getRhs().attribute(Attribute.Type.class).type;
		
		// First, translate lhs and store result in the target register.
		translate(e.getLhs(), target, freeRegisters, localVariables, code, data);

		// Second, determine register into which to store rhs, and create new
		// free registers list which doesn't include the target register for
		// this expression (since this will currently hold the stored result of
		// the lhs).
		Register rhsTarget = freeRegisters.get(0); // FIXME: implement spilling!
		ArrayList<Register> nFreeRegisters = new ArrayList<Register>(
				freeRegisters);
		nFreeRegisters.remove(target);

		translate(e.getRhs(), rhsTarget, nFreeRegisters, localVariables, code,
				data);

		// Finally, perform the binary operation.
		switch (e.getOp()) {
		case AND:
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.and,
					rhsTarget, target));
			break;
		case OR:
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.or,
					rhsTarget, target));
			break;
		case ADD:
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.add,
					rhsTarget, target));
			break;
		case SUB:
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.sub,
					rhsTarget, target));
			break;
		case MUL:
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.imul,
					rhsTarget, target));
			break;
		case DIV:
			// The idiv instruction is curious because you cannot control where
			// the result is stored. That is, the result is always stored into
			// the hdx:hax register pairing (where hdx = remainder, hax = quotient).
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov,
					target, HAX));
			// TODO: I'm not completely sure why we need this!
			instructions.add(new Instruction.Unit(Instruction.UnitOp.cltd));
			// FIXME: this trashes the HDX register which could be in use!
			instructions.add(new Instruction.Reg(Instruction.RegOp.idiv,
					rhsTarget));
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov,
					HAX, target));
			break;
		case REM:
			// The idiv instruction is curious because you cannot control where
			// the result is stored. That is, the result is always stored into
			// the hdx:has register pairing (where hdx = remainder, hax = quotient).
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov,
					target, HAX));
			// TODO: I'm not completely sure why we need this!			
			instructions.add(new Instruction.Unit(Instruction.UnitOp.cltd));
			// FIXME: this trashes the HDX register which could be in use!
			instructions.add(new Instruction.Reg(Instruction.RegOp.idiv,
					rhsTarget));
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov,
					HDX, target));
			break;
		case EQ:
		case NEQ:
		case LT:
		case LTEQ:
		case GT:
		case GTEQ: {
			// NOTE: this could be implemented more efficiently in many cases.
			// For example, if true is any non-zero number then we can just
			// perform a subtraction for equality and inequality.
			String trueLabel = freshLabel();
			String exitLabel = freshLabel();
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.cmp, rhsTarget,
					HDI));
			
			switch (e.getOp()) {
			case EQ:
				// FIXME: problem with compound types
				instructions.add(new Instruction.Addr(Instruction.AddrOp.jz, trueLabel));
				break;
			case NEQ:
				// FIXME: problem with compound types
				instructions.add(new Instruction.Addr(Instruction.AddrOp.jnz, trueLabel));
				break;
			case LT:
				instructions.add(new Instruction.Addr(Instruction.AddrOp.jl, trueLabel));
				break;
			case LTEQ:
				instructions.add(new Instruction.Addr(Instruction.AddrOp.jle, trueLabel));
				break;
			case GT:
				instructions.add(new Instruction.Addr(Instruction.AddrOp.jg, trueLabel));
				break;
			case GTEQ:
				instructions.add(new Instruction.Addr(Instruction.AddrOp.jge, trueLabel));
				break;
			}
						
			instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.mov,
					0, target));
			instructions.add(new Instruction.Addr(Instruction.AddrOp.jmp, exitLabel));
			instructions.add(new Instruction.Label(trueLabel));
			instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.mov,
					1, target));
			instructions.add(new Instruction.Label(exitLabel));
			break;
		}
		case APPEND:
			if (lhsType instanceof Type.Strung
					&& rhsType instanceof Type.Strung) {
				// Straightforward String concatenation
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, target, HDI));
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, rhsTarget, HSI));
				instructions.add(new Instruction.Addr(Instruction.AddrOp.call, "_str_append"));
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, HAX, target));
			} else if(lhsType instanceof Type.Strung) {
				// Straightforward String concatenation
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, target, HDI));
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, rhsTarget, HSI));
				String typeLabel = freshLabel();
				addTypeConstant(rhsType,typeLabel,data);				
				instructions.add(new Instruction.AddrRegReg(Instruction.AddrRegRegOp.lea,
						typeLabel, HIP, HDX));
				instructions.add(new Instruction.Addr(Instruction.AddrOp.call, "_str_left_append"));
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, HAX, target));
			} else if(rhsType instanceof Type.Strung) {
				// Straightforward String concatenation
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, target, HDI));
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, rhsTarget, HSI));
				String typeLabel = freshLabel();
				addTypeConstant(lhsType,typeLabel,data);				
				instructions.add(new Instruction.AddrRegReg(Instruction.AddrRegRegOp.lea,
						typeLabel, HIP, HDX));
				instructions.add(new Instruction.Addr(Instruction.AddrOp.call, "_str_right_append"));
				instructions.add(new Instruction.RegReg(
						Instruction.RegRegOp.mov, HAX, target));
			} else {
				throw new IllegalArgumentException("list append not implemented");
			}
			break;
		default:
			throw new IllegalArgumentException("Unknown binary operator: " + e);
		}
	}

	public void translate(Expr.Constant e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		
		List<Instruction> instructions = code.instructions;
        Object value = e.getValue();

        String label = freshLabel();
//        addTypeConstant(getWhileTypeFromJava(value), label = freshLabel(), data);  // already done after this method

        // check if target is free?
        if (freeRegisters.contains(target))
        {
            instructions.add(new Instruction.AddrRegReg(Instruction.AddrRegRegOp.lea,
                    label, HIP, target));

            // add Constant to data (Word is 2 bytes)
            if (value instanceof String)
                data.constants.add(new Constant.String(label, (String) e.getValue()));
            else if (value instanceof Long || value instanceof Double)    // 8 bytes
                data.constants.add(new Constant.Quad(label, (Long) value));
            else if (value instanceof Integer)   // 4 bytes
                data.constants.add(new Constant.Long(label, (Long) value));

        }



		// TODO: implement me!
	}

	public void translate(Expr.Cast e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		// TODO: implement me!
	}

	public void translate(Expr.IndexOf e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		// TODO: implement me!
	}

	public void translate(Expr.Invoke e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		List<Instruction> instructions = code.instructions;

		// First, determine the amount of space to reserve on the stack for
		// parameters and the return value (if applicable).
		WhileFile.FunDecl fd = functions.get(e.getName());
		int alignedWidth = determineCallerEnvironmentAlignedWidth(fd);

		// Second, create space on the stack for parameters and return value
		instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.sub,
				alignedWidth, HSP));

		// Third, translate invocation arguments and load them onto the stack.
		int offset = alignedWidth;

		List<Expr> arguments = e.getArguments();
		List<WhileFile.Parameter> parameters = fd.parameters;
		for (int i = 0; i != arguments.size(); ++i) {
			WhileFile.Parameter parameter = parameters.get(i);
			Expr argument = arguments.get(i);
			translate(argument, target, freeRegisters, localVariables, code,
					data);
			offset -= determineWidth(parameter.type);			
			writeToStack(parameter.type,target,HSP,offset,code,data);		
		}

		// Fourth, actually invoke the function
		String fn_name = "wl_" + fd.name;
		instructions.add(new Instruction.Addr(Instruction.AddrOp.call, fn_name));
		
		if (!(fd.ret instanceof Type.Void)) {
			// Fifth, extract the return value
			offset -= determineWidth(fd.ret);
			readFromStack(fd.ret,HSP,offset,target,code,data);			
		}
		
		// In principle, we'd like to return the stack pointer to its original
		// position here. However, in the case of a compound data type who's
		// address has been take we can't.
		// instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.add,
		//		alignedWidth, HSP));
	}

	public void translate(Expr.ListConstructor e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		// TODO: implement me!
	}

	public void translate(Expr.RecordAccess e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		
		// First, determine the field offset
		Type.Record type = (Type.Record) unwrap(e.getSource().attribute(Attribute.Type.class).type);
		int offset = determineFieldOffset(type,e.getName());
				
		// Second, translate source expression
		translate(e.getSource(), target, freeRegisters, localVariables, code, data);
		
		// Finally, perform indirect read
		instructions.add(new Instruction.ImmIndReg(Instruction.ImmIndRegOp.mov,
				offset, target, target));
	}
	
	public void translate(Expr.RecordConstructor e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		
		// First, get fields in sorted order!
		ArrayList<Pair<String, Expr>> fields = new ArrayList<Pair<String, Expr>>(
				e.getFields());
		sortFields(fields);
				
		// Create space on the stack for the resulting record
		Type.Record type = (Type.Record) unwrap(e.attribute(Attribute.Type.class).type);
		int width = determineWidth(type);
		int paddedWidth = determineAlignedStackWidth(width);
		// Create space for the stack frame, which consists of the local
		// variables.
		instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.sub,
				paddedWidth, HSP));
		
		// Second, translate fields in the appropriate order and push them onto
		// the stack in their appropriate order. This is a little tricky because
		// we need to flatten nested fields appropriately.	
		int offset = paddedWidth;
		for (int i = fields.size()-1; i >= 0; --i) {
			Pair<String,Expr> p = fields.get(i);			
			translate(p.second(), target, freeRegisters, localVariables, code, data);
			// Implement the assignment
			offset -= determineWidth(type.getFields().get(p.first()));
			writeToStack(type.getFields().get(p.first()), HDI, HSP, offset,
					code, data);
		}
		
		// Finally, create the target pointer from the stack pointer
		instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov, HSP,
				target));
		if (paddedWidth != width) {
			// This is necessary because we've had to ensure the stack is
			// aligned properly, which means there may be some extra padding
			// after the end of the record.
			instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.add,
					paddedWidth - width, target));
		}
	}

	public static void sortFields(List<Pair<String, Expr>> ofields) {
		Collections.sort(ofields, new Comparator<Pair<String, Expr>>() {
			public int compare(Pair<String, Expr> p1, Pair<String, Expr> p2) {
				return p1.first().compareTo(p2.first());
			}
		});
	}
	
	public void translate(Expr.Unary e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		List<Instruction> instructions = code.instructions;

		// First, translate lhs and store result in the target register.
		translate(e.getExpr(), target, freeRegisters, localVariables, code,
				data);

		// Finally, perform the binary operation.
		switch (e.getOp()) {
		case NOT:
			// First, perform logical not of all bites
			instructions
					.add(new Instruction.Reg(Instruction.RegOp.not, target));
			// Second, ensure only bit 1 may be set
			instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.and,
					1, target));
			break;
		case NEG:
			instructions
					.add(new Instruction.Reg(Instruction.RegOp.neg, target));
			break;
		default:
			throw new IllegalArgumentException("Unknown unary operator: " + e);
		}
	}

	public void translate(Expr.Variable e, Register target,
			List<Register> freeRegisters, Map<String, Integer> localVariables,
			X86File.Code code, X86File.Data data) {
		List<Instruction> instructions = code.instructions;

		// There are two cases we need to consider here: primitive values; and,
		// compound values. For the former, we load their value directly into
		// the target register. For the latter, we load a pointer to their value
		// directly into the target register.

		// Determine the offset within the stack of this local variable.
		int offset = localVariables.get(e.getName());

		// Extract the variable's type to help determine which case to handle
		Type type = e.attribute(Attribute.Type.class).type;
		
		// Finally, read the target into the target register
		readFromStack(type,HBP,offset,target,code,data);
	}

	// ==========================================
	// Other Helpers
	// ==========================================

	/**
	 * Copy a data value from a given register into a stack location offset from
	 * a base pointer. In the case of a compound value, then the source
	 * register is a pointer to a memory location and we need to perform an
	 * indirect copy.
	 * 
	 * @param type
	 *            Type of data being assigned
	 * @param source
	 *            Register to read data value from
	 * @param target
	 *            Based pointer into stack for assignment
	 * @param offset
	 *            Stack location offset from base pointer to assign to
	 */
	public void writeToStack(Type type, Register source, Register target,
			int offset, X86File.Code code, X86File.Data data) {
		
		List<Instruction> instructions = code.instructions;
				
		// There are two cases we need to consider here: primitive values;
		// and, compound values. For the former, we write their value directly
		// from the target register. For the latter, we copy data from a pointer
		// to their value over the given target.

		type = unwrap(type); // remove named types
		
		if (type instanceof Type.Bool || type instanceof Type.Char
				|| type instanceof Type.Int || type instanceof Type.Real
				|| type instanceof Type.Strung) {
			// These are the primitive types. Therefore, we just read their
			// value directly from the stack into the target register.
			instructions.add(new Instruction.RegImmInd(
					Instruction.RegImmIndOp.mov, HDI, offset, target));
		} else {
			// These are compound types. Basically, we just perform a bitwise
			// copy of each slot.
			int width = determineWidth(type);
			
			// Determine the number of slots required (note, width is guaranteed
			// to be a multiple of the natural word size).
			int nSlots = width / this.target.widthInBytes();
			int sourceOffset = width; 
			int targetOffset = offset + width;
			
			for(int i=0;i!=nSlots;++i) {
				// decrement offset by one slot
				targetOffset -= this.target.widthInBytes();
				sourceOffset -= this.target.widthInBytes();
				// Read slot references by source register into temporary
				// register.
				instructions.add(new Instruction.ImmIndReg(
						Instruction.ImmIndRegOp.mov, sourceOffset, source, HAX));
				// Writer temporary register into slot referenced by target register.				
				instructions.add(new Instruction.RegImmInd(
						Instruction.RegImmIndOp.mov, HAX, targetOffset, target));
			}
		}
	}
	
	/**
	 * Copy a data value to a given register from a stack location offset from a
	 * base pointer. In the case of a compound value, then we need to store the
	 * address of the structure into the target register.
	 * 
	 * @param type
	 *            Type of data being assigned
	 * @param source
	 *            Based pointer into stack for assignment
	 * @param offset
	 *            Stack location offset from base pointer to read from
	 * @param target
	 *            Register to write data value / pointer to
	 * 
	 */
	public void readFromStack(Type type, Register source, int offset,
			Register target, X86File.Code code, X86File.Data data) {
		List<Instruction> instructions = code.instructions;
		
		// There are two cases we need to consider here: primitive values;
		// and, compound values. For the former, we write their value directly
		// to the target register. For the latter, we write their address as a
		// pointer to the target register.
		
		type = unwrap(type); // remove named types
		
		if (type instanceof Type.Bool || type instanceof Type.Char
				|| type instanceof Type.Int || type instanceof Type.Real
				|| type instanceof Type.Strung) {
			// These are the primitive types. Therefore, we just read their
			// value directly from the stack into the target register.
			instructions.add(new Instruction.ImmIndReg(
					Instruction.ImmIndRegOp.mov, offset, source, target));
		} else {
			// These are all compound types. Therefore, we load the address of
			// their value on the stack into the target register.
			instructions.add(new Instruction.RegReg(Instruction.RegRegOp.mov,
					source, target));
			instructions.add(new Instruction.ImmReg(Instruction.ImmRegOp.add,
					offset, target));
		}
	}
	
	/**
	 * <p>
	 * Allocate every local variable declared in a function to an appropriate
	 * amount of space on the stack. The space is allocated in "slots", where
	 * each slot corresponds to an amount of space given by the architecture's
	 * "natural" size (i.e. 64bits on x86_64, etc). Obviously, this is not the
	 * most efficient approach and we could do better (since e.g. int's in While
	 * need only 32bits).
	 * </p>
	 * <p>
	 * <b>NOTE:</b> parameters are also allocated here, although they do not
	 * contribution to an increase in stack size since they are
	 * caller-allocated.
	 * </p>
	 * 
	 * @param function
	 *            Function for which to create the stack frame
	 * @param allocation
	 *            Map of variable names to their allocated position on the stack
	 * @return The total number of bytes which were allocated
	 */
	public int allocateStackFrame(WhileFile.FunDecl function,
			Map<String, Integer> allocation) {

		// First, allocate parameters. We need to include two natural words
		// to account for the caller return address, and the frame pointer.
		// Also, there maybe some padding of some sort.
		int offset = determineCallerEnvironmentAlignedWidth(function)
				+ (target.widthInBytes() * 2);
		
		List<WhileFile.Parameter> fun_params = function.parameters;
		for (int i = 0; i < fun_params.size(); i++) {
			WhileFile.Parameter p = fun_params.get(i);
			offset -= determineWidth(p.type);
			allocation.put(p.name, offset);
		}

		// Second, allocate special return value
		if(!(function.ret instanceof Type.Void)) {
			offset -= determineWidth(function.ret);
			allocation.put("$", offset);
		}
		
		// Finally, allocate remaining local variables
		return allocateStackFrame(function.statements, allocation);
	}

	private int allocateStackFrame(List<Stmt> statements,
			Map<String, Integer> allocation) {

		// First, we go through and determine the type of all declared
		// variables. During this process if we have two declarations for
		// variables with the same name, we retain the larger type. This
		// guarantees there is enough space for the variable in question.
		HashMap<String, Type> variables = new HashMap<String, Type>();
		extractLocalVariableTypes(statements, variables);

		int count = 0; 
		for (Map.Entry<String, Type> e : variables.entrySet()) {
			int width = determineWidth(e.getValue());
			count += width;
			allocation.put(e.getKey(), -count);
		}

		// Finally, round the size of the stack here, depending on the
		// architecture. That is, on x86_64 it needs to a multiple of
		// 16 bytes.

		return determineAlignedStackWidth(count);
	}

	/**
	 * Determine the type of every declared local variable. In cases where we
	 * have two local variables with the same name but different types, choose
	 * the physically largest type (in bytes).
	 * 
	 * @param statements
	 * @param allocation
	 */
	private void extractLocalVariableTypes(List<Stmt> statements,
			Map<String, Type> allocation) {
		for (Stmt stmt : statements) {
			if (stmt instanceof Stmt.VariableDeclaration) {
				Stmt.VariableDeclaration vd = (Stmt.VariableDeclaration) stmt;
				Type ot = allocation.get(vd.getName());
				Type nt = vd.getType();
				if (ot != null && determineWidth(ot) > determineWidth(nt)) {
					nt = ot;
				}
				allocation.put(vd.getName(), nt);
			} else if (stmt instanceof Stmt.IfElse) {
				Stmt.IfElse ife = (Stmt.IfElse) stmt;
				extractLocalVariableTypes(ife.getTrueBranch(), allocation);
				extractLocalVariableTypes(ife.getFalseBranch(), allocation);
			} else if (stmt instanceof Stmt.For) {
				Stmt.For fe = (Stmt.For) stmt;
				
				// Allocate loop variable 
				Stmt.VariableDeclaration vd = fe.getDeclaration();
				Type ot = allocation.get(vd.getName());
				Type nt = vd.getType();
				if (ot != null && determineWidth(ot) > determineWidth(nt)) {
					nt = ot;
				}
				allocation.put(vd.getName(), nt);
				
				// Explore loop body				
				extractLocalVariableTypes(fe.getBody(), allocation);
			} else if (stmt instanceof Stmt.While) {
				Stmt.While fe = (Stmt.While) stmt;
				extractLocalVariableTypes(fe.getBody(), allocation);
			}
		}
	}

	/**
	 * On some operating systems, the stack must be aligned to a specific
	 * amount. For example, on x86_64/MacOS it must be aligned to multiples of
	 * 16 bytes.
	 * 
	 * @param minimum
	 *            The minumum number of bytes required for the stack frame to
	 *            hold all the necessary local variables, etc.
	 * @return
	 */
	private int determineAlignedStackWidth(int minimum) {
		if (target == Target.MACOS_X86_64) {
			// round up to nearest 16 bytes
			int tmp = (minimum / 16) * 16;
			if (tmp < minimum) {
				tmp = tmp + 16;
			}
			return tmp;
		}
		return minimum;
	}

	/**
	 * Determine the width (in bytes) of this type. For simplicity we round
	 * everything to the nearest "natural" word size for the given architecture.
	 * For example, on x86_64, this function returns 8 for type bool. Obviously,
	 * this is not the most efficient.
	 * 
	 * @param type
	 * @return
	 */
	public int determineWidth(Type type) {
		if (type instanceof Type.Bool || type instanceof Type.Char
				|| type instanceof Type.Int || type instanceof Type.Real) {
			// The size of a machine word.
			return target.widthInBytes();
		} else if (type instanceof Type.Record) {
			Type.Record r = (Type.Record) type;
			int total = 0;
			for (Map.Entry<String, Type> e : r.getFields().entrySet()) {
				total += determineWidth(e.getValue());
			}
			return total;
		} else if (type instanceof Type.Strung) {
			// Always the size of a machine pointer.
			return target.widthInBytes();
		} else if (type instanceof Type.List) {
			// Always the size of a machine pointer.
			return target.widthInBytes();
		} else if (type instanceof Type.Union) {
			Type.Union r = (Type.Union) type;
			// Compute the maximum size of any bound.
			int width = 0;
			for (Type b : r.getBounds()) {
				width = Math.max(width, determineWidth(b));
			}
			// FIXME: this is broken because it does not include the type tag
			// itself.
			return width;
		} else if (type instanceof Type.Named) {
			return determineWidth(unwrap(type));
		} else {
			throw new IllegalArgumentException("Unknown type encountered: "
					+ type);
		}
	}

	public int determineCallerEnvironmentAlignedWidth(WhileFile.FunDecl fd) {
		int width = 0;
		for (WhileFile.Parameter p : fd.parameters) {
			width += determineWidth(p.type);
		}
		if (!(fd.ret instanceof Type.Void)) {
			width += determineWidth(fd.ret);
		}
		return determineAlignedStackWidth(width);
	}

	/**
	 * Determine the offset of a given field in a given type.
	 * 
//	 * @param e
//	 * @param target
//	 * @param freeRegisters
//	 * @param localVariables
//	 * @param code
//	 * @param data
	 */
	public int determineFieldOffset(Type.Record type, String field) {
		
		// First, get fields in sorted order!
		ArrayList<String> fields = new ArrayList<String>();
		for(Map.Entry<String,Type> entry : type.getFields().entrySet()) {
			fields.add(entry.getKey());
		}
		Collections.sort(fields);
		
		// Second, calculate offset of field we are reading
		int offset = determineWidth(type);
		for (int i = fields.size() - 1; i >= 0; --i) {
			String f = fields.get(i);
			offset -= determineWidth(type.getFields().get(field));
			if (field.equals(f)) {
				break;
			}			
		}
		
		return offset;
	}
	
	public Type unwrap(Type type) {
		if(type instanceof Type.Named) {
			Type.Named tn = (Type.Named) type; 
			WhileFile.TypeDecl td = types.get(tn.getName());
			return td.type;
		} else {
			return type;
		}
	}
	
	private static int labelIndex = 0;
	
	public static String freshLabel() {
		return "label" + labelIndex++;
	}
	
	/**
	 * <p>
	 * Create a data constant representing a While type. This encodes all
	 * information necessary to decode the type including, for example, field
	 * names etc.
	 * </p>
	 * <p>
	 * Each type begins with a tag which identifies what kind it is, followed by
	 * the payload (if applicable) which may contain other nested types.
	 * </p>
	 * 
	 * @param type
	 * @param data
	 */
	private void addTypeConstant(Type type, String label, X86File.Data data) {
		type = unwrap(type);
		
		if (type instanceof Type.Void) {
			addNaturalWordConstant(VOID_TAG, label, data);
		} else if (type instanceof Type.Bool) {
			addNaturalWordConstant(BOOL_TAG, label, data);
		} else if (type instanceof Type.Char) {
			addNaturalWordConstant(CHAR_TAG, label, data);
		} else if (type instanceof Type.Int) {
			addNaturalWordConstant(INT_TAG, label, data);
		} else if (type instanceof Type.Real) {
			addNaturalWordConstant(REAL_TAG, label, data);
        } else if (type instanceof Type.Strung) {
			addNaturalWordConstant(STRING_TAG, label, data);
		} else if (type instanceof Type.Record) {
			Type.Record r = (Type.Record) type;
			// First, get sorted fields
			Map<String,Type> fields = r.getFields(); 
			ArrayList<String> fieldNames = new ArrayList<String>(r.getFields().keySet());
			Collections.sort(fieldNames);			
			// Second, write type tag
			addNaturalWordConstant(RECORD_TAG, label, data);
			// Third, write the number of fields			
			addNaturalWordConstant(fieldNames.size(), null, data);
			for(String field : fieldNames) {
				// Fourth, each field consists of a  			
				addNaturalWordConstant(field.length(), null, data);
				data.constants.add(new Constant.String(null,field));
				addTypeConstant(fields.get(field),null,data);
			}			
		} else if (type instanceof Type.List) {
			Type.List l = (Type.List) type; 
			addNaturalWordConstant(7, label, data);
			addTypeConstant(l.getElement(),null,data);
		} else {
			throw new IllegalArgumentException("Unknown type encountered - "
					+ type);
		}
	}
	
	private void addNaturalWordConstant(long value, String label, X86File.Data data) {
		Constant item;
		
		switch(target.arch) {
		case X86_32:
			item = new Constant.Long(label, value);
			break;
		case X86_64:
			item = new Constant.Quad(label, value);
			break;
		default:
			throw new IllegalArgumentException("Unknown architecture encountered");
		}
		
		data.constants.add(item);
	}
	
	/**
	 * Add a standard main method which will be called by the operating system
	 * when this process is executed. This sequence is operating system
	 * dependent, and simply calls the translated <code>main()</code> method
	 * from the original while source file.
	 * 
//	 * @param xf
	 */
	private void addMainLauncher(X86File.Code code) {
		List<Instruction> instructions = code.instructions;
		instructions.add(new Instruction.Label("_main", 1, true)); // fixme
		instructions.add(new Instruction.Reg(Instruction.RegOp.push, HBP));
		instructions.add(new Instruction.Addr(Instruction.AddrOp.call,
				"wl_main"));
		instructions.add(new Instruction.Reg(Instruction.RegOp.pop, HBP));
		instructions.add(new Instruction.Unit(Instruction.UnitOp.ret));
	}

    private Type getWhileTypeFromJava(Object o)
    {
        if (o instanceof String)
            return new Type.Strung();
        else if (o instanceof Integer)
            return new Type.Int();
        else if (o instanceof Boolean)
            return new Type.Bool();
        else if (o instanceof Character)
            return new Type.Char();
        else if (o instanceof Double || o instanceof Float)
            return new Type.Real();
        else throw new IllegalArgumentException("Could not find type of constant");




    }

	/**
	 * Returns the head of a given registers family. For example, on
	 * <code>x86_64</code> the head of the <code>bx</code> family is
	 * <code>rbx</code>. Conversely, the head of the <code>bx</code> family is
	 * <code>ebx</code> on <code>x86_32</code>.
	 * 
	 * @param register
	 * @return
	 */
	private Register headOfFamily(Register register) {
		Register.Width width;
		switch (target.arch) {
		case X86_32:
			width = Register.Width.Long;
			break;
		case X86_64:
			width = Register.Width.Quad;
			break;
		default:
			throw new IllegalArgumentException("Invalid architecture: "
					+ target.arch);
		}
		return register.sibling(width);
	}

	private final Register HAX;
	private final Register HBX;
	private final Register HCX;
	private final Register HDX;
	private final Register HDI;
	private final Register HSI;
	private final Register HBP;
	private final Register HSP;
	private final Register HIP;

	public final List<Register> REGISTER_POOL;
	
	private final int VOID_TAG = 0;
	private final int BOOL_TAG = 1;
	private final int CHAR_TAG = 2;
	private final int INT_TAG = 3;
	private final int REAL_TAG = 4;
	private final int STRING_TAG = 5;
	private final int RECORD_TAG = 6;
	private final int LIST_TAG = 7;
}

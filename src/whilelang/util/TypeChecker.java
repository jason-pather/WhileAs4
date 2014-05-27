package whilelang.util;

import static whilelang.util.SyntaxError.internalFailure;

import java.util.*;

import whilelang.lang.*;
import static whilelang.util.SyntaxError.*;

/**
 * <p>
 * Responsible for ensuring that all types are used appropriately. For example,
 * that we only perform arithmetic operations on arithmetic types; that we only
 * access fields in records guaranteed to have those fields, etc.
 * </p>
 *
 * @author David J. Pearce
 *
 */
// List Append 3 prints 1, 2, 3, 4, 6 instead of 1, 2, 6
public class TypeChecker {
    private WhileFile file;
    private WhileFile.FunDecl function;
    private HashMap<String,WhileFile.FunDecl> functions;
    private HashMap<String,WhileFile.TypeDecl> types;

    public void check(WhileFile wf) {
        this.file = wf;
        this.functions = new HashMap<String,WhileFile.FunDecl>();
        this.types = new HashMap<String,WhileFile.TypeDecl>();

        for(WhileFile.Decl declaration : wf.declarations) {
            if(declaration instanceof WhileFile.FunDecl) {
                WhileFile.FunDecl fd = (WhileFile.FunDecl) declaration;
                this.functions.put(fd.name(), fd);
            } else if(declaration instanceof WhileFile.TypeDecl) {
                WhileFile.TypeDecl fd = (WhileFile.TypeDecl) declaration;
                this.types.put(fd.name(), fd);
            }
        }

        for(WhileFile.Decl declaration : wf.declarations) {
            if(declaration instanceof WhileFile.FunDecl) {
                check((WhileFile.FunDecl) declaration);
            }
        }
    }

    public void check(WhileFile.FunDecl fd) {
        this.function = fd;

        // First, initialise the typing environment
        HashMap<String,Type> environment = new HashMap<String,Type>();
        for (WhileFile.Parameter p : fd.parameters) {
            environment.put(p.name(), p.type);
        }

        // Second, check all statements in the function body
        check(fd.statements,environment);


    }

    public void check(List<Stmt> statements, Map<String,Type> environment) {
        for(Stmt s : statements) {
            check(s,environment);
        }
    }

    public void check(Stmt stmt, Map<String,Type> environment) {
        if(stmt instanceof Stmt.Assign) {
            check((Stmt.Assign) stmt, environment);
        } else if(stmt instanceof Stmt.Print) {
            check((Stmt.Print) stmt, environment);
        } else if(stmt instanceof Stmt.Return) {
            check((Stmt.Return) stmt, environment);
        } else if(stmt instanceof Stmt.VariableDeclaration) {
            check((Stmt.VariableDeclaration) stmt, environment);
        } else if(stmt instanceof Expr.Invoke) {
            check((Expr.Invoke) stmt, environment);
        } else if(stmt instanceof Stmt.IfElse) {
            check((Stmt.IfElse) stmt, environment);
        } else if(stmt instanceof Stmt.For) {
            check((Stmt.For) stmt, environment);
        } else if(stmt instanceof Stmt.While) {
            check((Stmt.While) stmt, environment);
        } else {
            internalFailure("unknown statement encountered (" + stmt + ")", file.filename,stmt);
        }
    }


    public void check(Stmt.VariableDeclaration stmt, Map<String,Type> environment) {
        if(environment.containsKey(stmt.getName())) {
            syntaxError("variable already declared: " + stmt.getName(),
                    file.filename, stmt);
        } else if(stmt.getExpr() != null) {
            Type type = check(stmt.getExpr(),environment);
            checkSubtype( stmt.getType(), type,stmt.getExpr()); // fixme other way
        }
        environment.put(stmt.getName(), stmt.getType());
    }

    public void check(Stmt.Assign stmt, Map<String,Type> environment) {
        Type leftType = check(stmt.getLhs(),environment);
        Type rightType = check(stmt.getRhs(),environment);
        checkSubtype(leftType, rightType, stmt.getRhs());
    }

    public void check(Stmt.Print stmt, Map<String,Type> environment) {
        check(stmt.getExpr(),environment);
    }


    public void check(Stmt.Return stmt, Map<String, Type> environment) {
        if(stmt.getExpr() != null) {
            // check that expr is a subtyp of the return type
            Type type = check(stmt.getExpr(),environment);
            checkSubtype(function.ret, type, stmt.getExpr());
        }
    }

    public void check(Stmt.IfElse stmt, Map<String,Type> environment) {
        Type cond = check(stmt.getCondition(),environment);
        checkSubtype(new Type.Bool(), cond, stmt.getCondition());
        check(stmt.getTrueBranch(),environment);
        check(stmt.getFalseBranch(),environment);
    }

    public void check(Stmt.For stmt, Map<String,Type> environment) {

        Stmt.VariableDeclaration vd = stmt.getDeclaration();
        check(vd,environment);

        // Clone the environment in order that the loop variable is only scoped
        // for the life of the loop itself.
        environment = new HashMap<String,Type>(environment);
        environment.put(vd.getName(), vd.getType());

        Type type = check(stmt.getCondition(),environment);
        checkSubtype(new Type.Bool(), type, stmt.getCondition());
        check(stmt.getIncrement(),environment);
        check(stmt.getBody(),environment);
    }

    public void check(Stmt.While stmt, Map<String,Type> environment) {
        Type type = check(stmt.getCondition(),environment);
        checkSubtype(new Type.Bool(), type, stmt.getCondition());
        check(stmt.getBody(),environment);
    }

    public Type check(Expr expr, Map<String,Type> environment) {
        Type type;

        if(expr instanceof Expr.Binary) {
            type = check((Expr.Binary) expr, environment);
        } else if(expr instanceof Expr.Cast) {
            type = check((Expr.Cast) expr, environment);
        } else if(expr instanceof Expr.Constant) {
            type = check((Expr.Constant) expr, environment);
        } else if(expr instanceof Expr.IndexOf) {
            type = check((Expr.IndexOf) expr, environment);
        } else if(expr instanceof Expr.Invoke) {
            type = check((Expr.Invoke) expr, environment);
        } else if(expr instanceof Expr.ListConstructor) {
            type = check((Expr.ListConstructor) expr, environment);
        } else if(expr instanceof Expr.RecordAccess) {
            type = check((Expr.RecordAccess) expr, environment);
        } else if(expr instanceof Expr.RecordConstructor) {
            type = check((Expr.RecordConstructor) expr, environment);
        } else if(expr instanceof Expr.Unary) {
            type = check((Expr.Unary) expr, environment);
        } else if(expr instanceof Expr.Variable) {
            type = check((Expr.Variable) expr, environment);
        } else {
            internalFailure("unknown expression encountered (" + expr + ")", file.filename,expr);
            return null; // dead code
        }

        // Save the type attribute so that subsequent stages can use it without
        // having to recalculate it from scratch.
        expr.attributes().add(new Attribute.Type(type));

        return type;
    }

    public Type check(Expr.Binary expr, Map<String,Type> environment) {
        Type leftType = check(expr.getLhs(), environment);
        Type rightType = check(expr.getRhs(), environment);

        if (expr.getOp() != Expr.BOp.APPEND
                && !equivalent(leftType, rightType, expr)) {
            syntaxError("operands must have identical types, found " + leftType
                    + " and " + rightType, file.filename, expr);
        }

        switch(expr.getOp()) {
            case AND:
            case OR:
                checkSubtype(new Type.Bool(),leftType,expr.getLhs());
                checkSubtype(new Type.Bool(),rightType,expr.getRhs());
                return leftType;
            case ADD:
            case SUB:
            case DIV:
            case MUL:
            case REM:
                checkInstanceOf(leftType,expr,Type.Int.class,Type.Real.class);
                return leftType;
            case EQ:
            case NEQ:
                return new Type.Bool();
            case LT:
            case LTEQ:
            case GT:
            case GTEQ:
                checkInstanceOf(leftType,expr,Type.Int.class,Type.Real.class);
                return new Type.Bool();
            case APPEND:
                leftType = checkInstanceOf(leftType,expr.getLhs(),Type.List.class,Type.Strung.class);
                rightType = checkInstanceOf(rightType,expr.getRhs(),Type.List.class,Type.Strung.class);
                if (equivalent(leftType, rightType, expr)){
                    return leftType;
                }
                else
                {
                    Type union = new Type.Union(Arrays.asList(new Type[]{leftType, rightType}));
                    return  union   ;

                }

//			if(leftType instanceof Type.Strung) {
//
//			} else if (!equivalent(leftType, rightType, expr)) {
//				syntaxError("operands must have identical types, found " + leftType
//						+ " and " + rightType, file.filename, expr);
//			}
            default:
                internalFailure("unknown unary expression encountered (" + expr + ")", file.filename,expr);
                return null; // dead code
        }
    }

    public Type check(Expr.Cast expr, Map<String,Type> environment) {
        Type srcType = check(expr.getSource(),environment);
        checkCast(expr.getType(), srcType, expr.getSource());
        return expr.getType();
    }

    public Type check(Expr.Constant expr, Map<String,Type> environment) {
        Object constant = expr.getValue();

        if(constant instanceof Boolean) {
            return new Type.Bool();
        } else if(constant instanceof Character) {
            return new Type.Char();
        } else if(constant instanceof Integer) {
            return new Type.Int();
        } else if(constant instanceof Double) {
            return new Type.Real();
        } else if(constant instanceof String) {
            return new Type.Strung();
        } else if(constant == null) {
            return new Type.Null();
        } else {
            internalFailure("unknown constant encountered (" + expr + ")", file.filename,expr);
            return null; // dead code
        }
    }

    public Type check(Expr.IndexOf expr, Map<String, Type> environment) {
        Type srcType = check(expr.getSource(), environment);
        Type indexType = check(expr.getIndex(), environment);
        checkSubtype(new Type.Int(), indexType, expr.getIndex());
        srcType = checkInstanceOf(srcType, expr.getSource(), Type.List.class,
                Type.Strung.class);
        if (srcType instanceof Type.Strung) {
            return new Type.Char();
        } else {
            return ((Type.List) srcType).getElement();
        }
    }

    public Type check(Expr.Invoke expr, Map<String,Type> environment) {
        WhileFile.FunDecl fn = functions.get(expr.getName());
        List<Expr> arguments = expr.getArguments();
        List<WhileFile.Parameter> parameters = fn.parameters;
        if(arguments.size() != parameters.size()) {
            syntaxError("incorrect number of arguments to function",
                    file.filename, expr);
        }
        for(int i=0;i!=parameters.size();++i) {
            Type argument = check(arguments.get(i),environment);
            Type parameter = parameters.get(i).type;
            checkSubtype(parameter,argument,arguments.get(i));
        }
        return fn.ret;
    }

    public Type check(Expr.ListConstructor expr, Map<String,Type> environment) {
        ArrayList<Type> types = new ArrayList<Type>();
        List<Expr> arguments = expr.getArguments();
        for(Expr argument : arguments) {
            types.add(check(argument,environment));
        }
        // Now, simplify the list of types (note this is not the best way to do
        // this, but it is sufficient for our purposes here).
        ArrayList<Type> ntypes = new ArrayList<Type>();
        for(int i=0;i<types.size();++i) {
            Type iType = types.get(i);
            boolean subsumed = false;
            for(int j=i+1;j<types.size();++j) {
                Type jType = types.get(j);
                if(equivalent(iType,jType,expr)) {
                    subsumed=true;
                    break;
                }
            }
            if(!subsumed) {
                ntypes.add(iType);
            }
        }
        if(ntypes.size() > 1) {
            return new Type.List(new Type.Union(ntypes));
        } else if(ntypes.size() == 1){
            return new Type.List(ntypes.get(0));
        } else {
            return new Type.List(new Type.Void());
        }
    }

    public Type check(Expr.RecordAccess expr, Map<String,Type> environment) {
        Type srcType = check(expr.getSource(),environment);
        Type.Record recordType = (Type.Record) checkInstanceOf(srcType,
                expr.getSource(), Type.Record.class);
        if(!recordType.getFields().containsKey(expr.getName())) {
            syntaxError("expected type to contain field: " + expr.getName(),
                    file.filename, expr);
        }
        return recordType.getFields().get(expr.getName());
    }

    public Type check(Expr.RecordConstructor expr, Map<String,Type> environment) {
        HashMap<String,Type> types = new HashMap<String,Type>();
        List<Pair<String,Expr>> arguments = expr.getFields();

        for(Pair<String,Expr> p : arguments) {
            types.put(p.first(),check(p.second(),environment));
        }

        return new Type.Record(types);
    }

    public Type check(Expr.Unary expr, Map<String,Type> environment) {
        Type type = check(expr.getExpr(), environment);
        switch(expr.getOp()) {
            case NEG:
                checkInstanceOf(type,expr.getExpr(),Type.Int.class,Type.Real.class);
                return type;
            case NOT:
                checkSubtype(new Type.Bool(),type,expr.getExpr());
                return type;
            case LENGTHOF:
                checkInstanceOf(type,expr.getExpr(),Type.List.class,Type.Strung.class);
                return new Type.Int();
            default:
                internalFailure("unknown unary expression encountered (" + expr + ")", file.filename,expr);
                return null; // dead code
        }
    }

    public Type check(Expr.Variable expr, Map<String, Type> environment) {
        Type type = environment.get(expr.getName());
        if (type == null) {
            syntaxError("unknown variable encountered: " + expr.getName(),
                    file.filename, expr);
        }

        return type;
    }

    /**
     * Check that a given type t2 is an instance of of another type t1. This
     * method is useful for checking that a type is, for example, a List type.
     *
     * @param //t1
     * @param type
     * @param element
     *            Used for determining where to report syntax errors.
     * @return
     */
    public Type checkInstanceOf(Type type,
                                SyntacticElement element, Class<?>... instances) {

        if(type instanceof Type.Named) {
            Type.Named tn = (Type.Named) type;
            if (types.containsKey(tn.getName())) {
                Type body = types.get(tn.getName()).type;
                return checkInstanceOf(body, element, instances);
            } else {
                syntaxError("unknown type encountered: " + type, file.filename,
                        element);
            }
        }
        for (Class<?> instance : instances) {
            if (instance.isInstance(type)) {
                // This cast is clearly unsafe. It relies on the caller of this
                // method to do the right thing.
                // fixme
                return type;
            }
        }

        // Ok, we're going to fail with an error message. First, let's build up
        // a useful human-readable message.

        String msg = "";
        boolean firstTime = true;
        for (Class<?> instance : instances) {
            if(!firstTime) {
                msg = msg + " or ";
            }
            firstTime=false;

            if (instance.getName().endsWith("Bool")) {
                msg += "bool";
            } else if (instance.getName().endsWith("Char")) {
                msg += "char";
            } else if (instance.getName().endsWith("Int")) {
                msg += "int";
            } else if (instance.getName().endsWith("Real")) {
                msg += "real";
            } else if (instance.getName().endsWith("Strung")) {
                msg += "string";
            } else if (instance.getName().endsWith("List")) {
                msg += "list";
            } else if (instance.getName().endsWith("Record")) {
                msg += "record";
            } else {
                internalFailure("unknown type instanceof encountered ("
                        + instance.getName() + ")", file.filename, element);
                return null;
            }
        }

        syntaxError("expected instance of " + msg + ", found " + type,
                file.filename, element);
        return null;
    }

    /**
     * Check that a given type t2 is a subtype of another type t1.
     *
     * @param t1 Supertype to check
     * @param t2 Subtype to check
     * @param element
     *            Used for determining where to report syntax errors.
     */
    public void checkSubtype(Type t1, Type t2, SyntacticElement element) {
        if(!isSubtype(t1,t2,element)) {
            syntaxError("expected type " + t1 + ", found " + t2, file.filename,
                    element);
        }
    }

    /**
     * Check that a given type t2 is a subtype of another type t1.
     *
     * @param t1 Supertype to check
     * @param t2 Subtype to check
     * @param element
     *            Used for determining where to report syntax errors.
     */
    public boolean isSubtype(Type t1, Type t2, SyntacticElement element) {
        if (t2 instanceof Type.Void) {
            // OK
        } else if (t1 instanceof Type.Null && t2 instanceof Type.Null) {
            // OK
        } else if (t1 instanceof Type.Bool && t2 instanceof Type.Bool) {
            // OK
        } else if (t1 instanceof Type.Char && t2 instanceof Type.Char) {
            // OK
        } else if (t1 instanceof Type.Int && t2 instanceof Type.Int) {
            // OK
        } else if (t1 instanceof Type.Real && t2 instanceof Type.Real) {
            // OK
        } else if (t1 instanceof Type.Strung && t2 instanceof Type.Strung) {
            // OK
        } else if (t1 instanceof Type.List && t2 instanceof Type.List) {
            Type.List l1 = (Type.List) t1;
            Type.List l2 = (Type.List) t2;
            // The following is safe because While has value semantics. In a
            // conventional language, like Java, this is not safe because of
            // references.
            return isSubtype(l1.getElement(),l2.getElement(),element);
        } else if (t1 instanceof Type.Record && t2 instanceof Type.Record) {
            Type.Record l1 = (Type.Record) t1;
            Type.Record l2 = (Type.Record) t2;
            Map<String,Type> l1Fields = l1.getFields();
            Map<String,Type> l2Fields = l2.getFields();
            if(l1Fields.keySet().equals(l2Fields.keySet())) {
                for(Map.Entry<String,Type> p : l1Fields.entrySet()) {
                    if(!isSubtype(p.getValue(),l2Fields.get(p.getKey()),element)) {
                        return false;
                    }
                }
            } else {
                return false;
            }
        } else if (t1 instanceof Type.Named) {
            Type.Named tn = (Type.Named) t1;
            if (types.containsKey(tn.getName())) {
                Type body = types.get(tn.getName()).type;
                return isSubtype(body, t2, element);    // todo is this right
            } else {
                syntaxError("unknown type encountered: " + t1, file.filename,
                        element);
            }
        } else if (t2 instanceof Type.Named) {
            Type.Named tn = (Type.Named) t2;
            if (types.containsKey(tn.getName())) {
                Type body = types.get(tn.getName()).type;
                return isSubtype(t1, body, element);
            } else {
                syntaxError("unknown type encountered: " + t2, file.filename,
                        element);
            }
        } else if (t1 instanceof Type.Union) {
            Type.Union u1 = (Type.Union) t1;
            for(Type b1 : u1.getBounds()) {
                if(isSubtype(b1,t2,element)) {
                    return true;
                }
            }
            return false;
        } else if (t2 instanceof Type.Union) {
            Type.Union u2 = (Type.Union) t2;
            for(Type b2 : u2.getBounds()) {
                if(!isSubtype(t1,b2,element)) { // todo !
                    return false;
                }
            }
        } else {
            return false;
        }
        return true;
    }

    /**
     * Determine whether two given types are euivalent. Identical types are always
     * equivalent. Furthermore, e.g. "int|null" is equivalent to "null|int".
     *
     * @param t1
     *            first type to compare
     * @param t2
     *            second type to compare
     */
    public boolean equivalent(Type t1, Type t2, SyntacticElement element) {
        return isSubtype(t1,t2,element) && isSubtype(t2,t1,element);
    }

    /**
     * Check that a given type t2 is a castable to another type t1.
     *
     * @param t1
     *            Supertype to check
     * @param t2
     *            Subtype to check
     * @param element
     *            Used for determining where to report syntax errors.
     */
    public void checkCast(Type t1, Type t2, SyntacticElement element) {
        if (t1 instanceof Type.Null && t2 instanceof Type.Null) {
            // OK
        } else if (t1 instanceof Type.Bool && t2 instanceof Type.Bool) {
            // OK
        } else if (t1 instanceof Type.Char && t2 instanceof Type.Char) {
            // OK
        } else if (t1 instanceof Type.Int && t2 instanceof Type.Int) {
            // OK
        } else if (t1 instanceof Type.Real
                && (t2 instanceof Type.Real || t2 instanceof Type.Int)) {
            // OK
        } else if (t1 instanceof Type.Strung && t2 instanceof Type.Strung) {
            // OK
        } else if (t1 instanceof Type.List && t2 instanceof Type.List) {
            Type.List l1 = (Type.List) t1;
            Type.List l2 = (Type.List) t2;
            // The following is safe because While has value semantics. In a
            // conventional language, like Java, this is not safe because of
            // references.
            checkCast(l1.getElement(), l2.getElement(), element);
        } else if (t1 instanceof Type.Record && t2 instanceof Type.Record) {
            Type.Record l1 = (Type.Record) t1;
            Type.Record l2 = (Type.Record) t2;
            Map<String,Type> l1Fields = l1.getFields();
            Map<String,Type> l2Fields = l2.getFields();
            if(l1Fields.keySet().equals(l2Fields.keySet())) {
                for(Map.Entry<String,Type> p : l1Fields.entrySet()) {
                    checkCast(p.getValue(),l2Fields.get(p.getKey()),element);
                }
            } else {
                syntaxError("expected type " + t1
                        + ", found " + t2, file.filename, element);
            }
        } else if (t1 instanceof Type.Named) {
            Type.Named tn = (Type.Named) t1;
            if (types.containsKey(tn.getName())) {
                Type body = types.get(tn.getName()).type;
                checkCast(body, t2, element);
            } else {
                syntaxError("unknown type encountered: " + t1, file.filename,
                        element);
            }
        } else if (t2 instanceof Type.Named) {
            Type.Named tn = (Type.Named) t2;
            if (types.containsKey(tn.getName())) {
                Type body = types.get(tn.getName()).type;
                checkCast(t1, body, element);
            } else {
                syntaxError("unknown type encountered: " + t2, file.filename,
                        element);
            }
        } else {
            syntaxError("expected type " + t1 + ", found " + t2, file.filename,
                    element);
        }
    }
}

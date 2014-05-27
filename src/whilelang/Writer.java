package whilelang;

import jasm.attributes.Code;
import jasm.lang.*;
import whilelang.lang.Expr;
import whilelang.lang.Stmt;
import whilelang.lang.Type;
import whilelang.lang.WhileFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Created by jasonpather on 1/05/14.
 */
public class Writer {

//    can try to hack some way of getting a main method > executable java class file
//    or can try to keep adding functionality to pass tests

    private final WhileFile whileFile;
    private final ArrayList<WhileFile.Decl> decls;
    private HashMap<String, Type> variableTypes;

    public Writer(WhileFile ast)
    {
        this.whileFile = ast;
        this.decls = ast.declarations;
        this.variableTypes = new HashMap<String, Type>();


    }

    public ClassFile write(String filename) {


        ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
        modifiers.add(Modifier.ACC_PUBLIC);
        // Create an empty class file

        // int version, JvmType.Clazz type, JvmType.Clazz superClazz,
        // List<JvmType.Clazz> interfaces, List<Modifier> modifiers, BytecodeAttribute... attributes
        ClassFile cf = new ClassFile(
                49, // Java 1.5 or later
                new JvmType.Clazz("",filename.substring(0, filename.lastIndexOf("."))), // class
                JvmTypes.JAVA_LANG_OBJECT, // superclass is Object
                Collections.EMPTY_LIST, // implements no interfaces
                modifiers); // which is public




        for (WhileFile.Decl decl : decls)
        {
            if (decl instanceof WhileFile.FunDecl)
            {
                modifiers = writeFunction(modifiers, cf, (WhileFile.FunDecl) decl);
            }
            else if (decl instanceof WhileFile.ConstDecl)
            {

            }
            else if (decl instanceof WhileFile.Parameter)
            {

            }
            else if (decl instanceof WhileFile.TypeDecl)
            {

            }
        }


        return cf;
    }



    private ArrayList<Modifier> writeFunction(ArrayList<Modifier> modifiers, ClassFile cf, WhileFile.FunDecl decl)
    {
        WhileFile.FunDecl func = (WhileFile.FunDecl)decl;
        String name = func.name;
        JvmType ret = getJvmType(func.ret);
        ArrayList<JvmType> params = getJvmTypes (func.parameters);
        ArrayList<Stmt> stmts = func.statements;

        // store slot number of local variableTypes
        HashMap<String, Integer> locals= new HashMap<String, Integer>();
        int index = 0;


        // add method heading
        modifiers = new ArrayList<Modifier>(modifiers);
        modifiers.add(Modifier.ACC_STATIC);
        modifiers.add(Modifier.ACC_PUBLIC);
        ClassFile.Method method = new ClassFile.Method(
                name, //  method name
                new JvmType.Function( // is function
                        ret, // return type
                        params // params
                ),
                modifiers // which is static public
        );

        cf.methods().add(method);


        // add method statements
        ArrayList<Bytecode> bytecodes = new ArrayList<Bytecode>();

        writeStmts(ret, stmts, locals, index, bytecodes);


        // add completed mthoed
        method.attributes().add(new Code(bytecodes, Collections.EMPTY_LIST, method));
//        printBytecode(bytecodes); // todo
        return modifiers;
    }

    private void writeStmts(JvmType ret, ArrayList<Stmt> stmts, HashMap<String, Integer> locals, int index, ArrayList<Bytecode> bytecodes) {
        for (Stmt stmt : stmts)
        {
            if (stmt instanceof Stmt.Assign)
            {
                writeAssign(locals, bytecodes, (Stmt.Assign)stmt);


            }
            else if (stmt instanceof Stmt.For)
            {

            }
            else if (stmt instanceof Stmt.IfElse)
            {
                writeIfElse(locals, index, bytecodes, (Stmt.IfElse)stmt, ret);

            }
            else if (stmt instanceof  Stmt.Return)
            {
                writeReturn(locals, index, bytecodes, (Stmt.Return) stmt, ret);

            }
            else if (stmt instanceof Stmt.VariableDeclaration)
            {
                index = writeVarDecl(locals, index, bytecodes, (Stmt.VariableDeclaration) stmt);
            }

        }
    }

    private void writeIfElse(HashMap<String, Integer> locals, int index, ArrayList<Bytecode> bytecodes, Stmt.IfElse stmt, JvmType ret) {


        Expr cond = stmt.getCondition();    //compare
        JvmType jvmType = getType(cond);
        resolveExpr(locals, bytecodes, cond, jvmType);

        List<Stmt> trueBr = stmt.getTrueBranch();
        writeStmts(jvmType, (ArrayList<Stmt>) trueBr, locals, index, bytecodes);
        bytecodes.add(new Bytecode.Goto("end"));

        bytecodes.add(new Bytecode.Label("false"));
        List<Stmt> falseBr = stmt.getFalseBranch();
        writeStmts(jvmType, (ArrayList<Stmt>) falseBr, locals, index, bytecodes);   // write both sides on stack
        bytecodes.add(new Bytecode.Label("end"));
    }

    // break it down and get type
    private JvmType getType(Expr expr) {
        if (expr instanceof Expr.Variable)
        {
            Type type = variableTypes.get(((Expr.Variable) expr).getName());
            return getJvmType(type);
        }
        else if (expr instanceof Expr.Constant)
        {
            JvmType type = getConstantType(((Expr.Constant) expr).getValue());

            return type;
        }
        else if (expr instanceof Expr.Binary){
            return getType(((Expr.Binary) expr).getLhs());
//            getType(((Expr.Binary) expr).getRhs());
        }
        else if (expr instanceof Expr.Unary)
            return getType(((Expr.Unary) expr).getExpr());

        else throw new  IllegalArgumentException("Cannot do expr type");

    }


    private void writeAssign(HashMap<String, Integer> locals, ArrayList<Bytecode> bytecodes, Stmt.Assign stmt) {
        Expr lhs = stmt.getLhs();
        Expr rhs = stmt.getRhs();

        if (lhs instanceof Expr.Variable)
        {
            String name = ((Expr.Variable) lhs).getName();
            int slot = locals.get(name);
            Type type = variableTypes.get(name);
            resolveExpr(locals, bytecodes, rhs, getJvmType(type));
            bytecodes.add(new Bytecode.Store(slot, getJvmType(type)));
        }




    }


    private void printBytecode(ArrayList<Bytecode> bytecodes) {
        System.out.println("Bytecodes:");
        System.out.println("==========");
        for (Bytecode b : bytecodes)
            System.out.println(b);
        System.out.println("==========");
    }

    private int writeVarDecl(HashMap<String, Integer> locals, int index, ArrayList<Bytecode> bytecodes, Stmt.VariableDeclaration stmt)
    {
        String varName = stmt.getName();
        Type whileType = stmt.getType();
        Expr expr = stmt.getExpr();


        // store whilelang.Type of variable
        variableTypes.put(varName, whileType);

        // get JvmType of variable
        JvmType jvmType = getJvmType(whileType);

        // store as local variable, eg. [1, x]
        locals.put(varName, index++);

        // if it equals null, it must be just a "int i;" declaration.
        // don't need to do musch
        if (expr != null)
        {
            resolveExpr(locals, bytecodes, expr, jvmType);
            bytecodes.add(new Bytecode.Store(locals.get(varName), jvmType));
        }

        return index;
    }

    // Basically, load the expr onto the stack. If its a variable, find it;s value, if it's binary,
    // break it down
    // todo be areful when uing javmType sometimes it is just the return type
    // if it finds a variable r costnat, return its type
    private void resolveExpr(HashMap<String, Integer> locals, ArrayList<Bytecode> bytecodes, Expr expr, JvmType jvmType) {

        if (expr instanceof Expr.Constant)
        {
            bytecodes.add(writeConstant(expr));

        }
        else if (expr instanceof Expr.Variable)
        {

            int slot = locals.get(((Expr.Variable) expr).getName());
            Type type = variableTypes.get(((Expr.Variable) expr).getName());
            bytecodes.add(writeLoad(slot, getJvmType(type)));  // eg. iload_2

        }
        else if (expr instanceof Expr.Unary)
        {
            resolveExpr(locals, bytecodes, ((Expr.Unary) expr).getExpr(), jvmType);
            Expr.UOp op = ((Expr.Unary) expr).getOp();
            if (op == Expr.UOp.NEG)
            {
                bytecodes.add(new Bytecode.Neg(jvmType));
            }
            else throw new IllegalArgumentException("Cannot write this operator.");

        }
        else if (expr instanceof Expr.Binary)
        {
            resolveExpr(locals, bytecodes, ((Expr.Binary) expr).getLhs(), jvmType);
            resolveExpr(locals, bytecodes, ((Expr.Binary) expr).getRhs(), jvmType);
            Expr.BOp op = ((Expr.Binary) expr).getOp();
            // if it's add, mul, div, rem...
            if (hasOpOrdinal(op))
            {
                int opInt = getOpOrdinal(((Expr.Binary) expr).getOp());
                bytecodes.add(new Bytecode.BinOp(opInt, jvmType));
            }
            // if it's equals, gt, lt...
            else writeComp(locals, bytecodes, expr, jvmType, op);

        }
        else if (expr instanceof Expr.Cast)
        {

        }
        else if (expr instanceof Expr.IndexOf)
        {

        }
        else if (expr instanceof Expr.Invoke)
        {

        }
        else if (expr instanceof Expr.RecordAccess)
        {

        }
        else if (expr instanceof Expr.RecordConstructor)
        {

        }
        else if (expr instanceof Expr.ListConstructor)
        {

        }
        else throw new IllegalArgumentException("Could not determine expr type");

    }

    private void writeComp(HashMap<String, Integer> locals, ArrayList<Bytecode> bytecodes, Expr expr, JvmType jvmType, Expr.BOp op) {
        int opInt = getCompOrdinal(op);
        bytecodes.add(new Bytecode.Cmp(jvmType, opInt));
        Bytecode.IfMode ifMode = getIfMode(op);
        bytecodes.add(new Bytecode.If(ifMode, "false"));
    }

    private Bytecode.IfMode getIfMode(Expr.BOp op) {
        if (op == Expr.BOp.EQ) return Bytecode.IfMode.EQ;
        else if (op == Expr.BOp.NEQ) return Bytecode.IfMode.NE;
        else if (op == Expr.BOp.LT) return Bytecode.IfMode.LT;
        else if (op == Expr.BOp.GTEQ) return Bytecode.IfMode.GE;
        else if (op == Expr.BOp.GT) return Bytecode.IfMode.GT;
        else if (op == Expr.BOp.LTEQ) return Bytecode.IfMode.LE;
        else if (op == Expr.BOp.LTEQ) return Bytecode.IfMode.LE;
        else throw new IllegalArgumentException("Could not get if mode");
    }

    private int getCompOrdinal(Expr.BOp op) {
        if (op == Expr.BOp.EQ)
            return 0;
        else if (op == Expr.BOp.LT)
            return 1;
        else if (op == Expr.BOp.GT)
            return 2;
        else throw new IllegalArgumentException("Could not find ordinal for op");
    }

    private boolean hasOpOrdinal(Expr.BOp op) {
        if (op == Expr.BOp.ADD || op == Expr.BOp.SUB ||
                op == Expr.BOp.DIV || op == Expr.BOp.MUL ||
                op == Expr.BOp.REM || op == Expr.BOp.AND ||
                op == Expr.BOp.OR)
            return true;
        return false;
    }

    private int getOpOrdinal(Expr.BOp op) {
        if (op == Expr.BOp.ADD)
            return 0;
        else if (op == Expr.BOp.SUB)
            return 1;
        else if (op == Expr.BOp.MUL)
            return 2;
        else if (op == Expr.BOp.DIV)
            return 3;
        else if (op == Expr.BOp.REM)
            return 4;
        else if (op == Expr.BOp.AND)
            return 8;
        else if (op == Expr.BOp.OR)
            return 9;
        else throw new IllegalArgumentException("Could not recognise op "+op.toString());
    }

    private void writeReturn(HashMap<String, Integer> locals, int index, ArrayList<Bytecode> bytecodes, Stmt.Return stmt, JvmType returnType) {
        Expr expr = stmt.getExpr();
        resolveExpr(locals, bytecodes, expr, returnType);
        bytecodes.add(new Bytecode.Return(returnType));

    }






    // returns bytecode for writing loading constant to stack, eg iconst_1
    private Bytecode writeConstant(Expr expr) {
        Object o = ((Expr.Constant) expr).getValue();
        return new Bytecode.LoadConst(o);
    }

    private Bytecode writeConstant(Object obj){
        return new Bytecode.LoadConst(obj);
    }
    private Bytecode writeLoad(int slot, JvmType type)
    {
        return new Bytecode.Load(slot, type);    }


    private ArrayList<JvmType> getJvmTypes(ArrayList<WhileFile.Parameter> parameters) {
        ArrayList<JvmType> jvmTypes = new ArrayList<JvmType>();
        for (WhileFile.Parameter param : parameters)
        {
            jvmTypes.add(getJvmType(param.type));
        }

        return jvmTypes;
    }

    private JvmType getConstantType(Object object)
    {
        if (object instanceof Integer)
            return new JvmType.Int();
        else if (object instanceof Boolean)
            return new JvmType.Bool();
        else if (object instanceof Double)
            return new JvmType.Double();
        else if (object instanceof Float)
            return new JvmType.Float();
        else if (object instanceof Character)
            return new JvmType.Char();
        else if (object instanceof String)
            return JvmTypes.JAVA_LANG_STRING;
        else throw new IllegalArgumentException("Cannot find type of object");
    }

    // Convert from While types to Java types.
    private JvmType getJvmType(Type type) {
        if (type instanceof Type.Bool)
            return new JvmType.Bool();
        else if (type instanceof Type.Char)
            return new JvmType.Char();
        else if (type instanceof Type.Real)
            return new JvmType.Double();
        else if (type instanceof Type.Int)
            return new JvmType.Int();
        else if (type instanceof Type.Strung)
            return JvmTypes.JAVA_LANG_STRING;
        else if (type instanceof Type.Void)
            return JvmTypes.T_VOID;
        else if ( type instanceof Type.Null)
            return JvmTypes.JAVA_LANG_OBJECT;




        else throw new IllegalArgumentException("Could not convert from While type fo Java type");



    }





}

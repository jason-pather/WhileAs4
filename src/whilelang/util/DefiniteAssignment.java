package whilelang.util;

import static whilelang.util.SyntaxError.internalFailure;
import static whilelang.util.SyntaxError.syntaxError;

import java.util.*;
import java.util.concurrent.ThreadFactory;

import whilelang.lang.Expr;
import whilelang.lang.Stmt;
import whilelang.lang.WhileFile;

/**
 * Responsible for checking that all variables are defined before they are used.
 * The algorithm for checking this involves a depth-first search through the
 * control-flow graph of the method. Throughout this, a list of the defined
 * variables is maintained.
 *
 * @author David J. Pearce
 *
 */

// The issue is that the current algorithm simply sees that y is not assigned in
// in one of the execution paths as blows up, even though that path just returns
// a constant and does not use y

// for each bracnh say tru or false wehter we can actuall yget out of the bracj
//

// if any check method returns a false then the flag must stay false;
public class DefiniteAssignment {
    private WhileFile file;
    private WhileFile.FunDecl function;
    private HashSet<String> constants;

    public void check(WhileFile wf) {
        this.file = wf;
        this.constants = new HashSet<String>();
        for(WhileFile.Decl declaration : wf.declarations) {
            if(declaration instanceof WhileFile.ConstDecl) {
                WhileFile.ConstDecl cd = (WhileFile.ConstDecl) declaration;
                this.constants.add(cd.name());
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

        // First, initialise the environment with all parameters (since these
        // are assumed to be definitely assigned)
        HashSet<String> environment = new HashSet<String>(constants);
        for (WhileFile.Parameter p : fd.parameters) {
            environment.add(p.name());
        }

        // Second, check all statements in the function body
        check(fd.statements,environment);
    }

    /**
     * Check that all variables used in a given list of statements are definitely
     * assigned. Furthermore, update the set of definitely assigned variables to
     * include any which are definitely assigned at the end of these statements.
     *
     * @param statements
     *            The list of statements to check.
     * @param environment
     *            The set of variables which are definitely assigned.
     */
    public boolean check(List<Stmt> statements, Set<String> environment) {
        boolean flag = true;
        for(Stmt s : statements) {
            flag = flag && check(s, environment);

        }
        return flag;

    }

    /**
     * Check that all variables used in a given statement are definitely
     * assigned. Furthermore, update the set of definitely assigned variables to
     * include any which are definitely assigned after this statement.
     *
     * @param //statement
     *            The statement to check.
     * @param environment
     *            The set of variables which are definitely assigned.
     */
    public boolean check(Stmt stmt, Set<String> environment) {
        boolean flag = true;
        if(stmt instanceof Stmt.Assign) {
            flag = flag && check((Stmt.Assign) stmt, environment);
        } else if(stmt instanceof Stmt.Print) {
            flag = flag && check((Stmt.Print) stmt, environment);
        } else if(stmt instanceof Stmt.Return) {
            flag = flag && check((Stmt.Return) stmt, environment);
        } else if(stmt instanceof Stmt.VariableDeclaration) {
            flag = flag && check((Stmt.VariableDeclaration) stmt, environment);
        } else if(stmt instanceof Expr.Invoke) {
            flag = flag && check((Expr.Invoke) stmt, environment);
        } else if(stmt instanceof Stmt.IfElse) {
            flag = flag && check((Stmt.IfElse) stmt, environment);
        } else if(stmt instanceof Stmt.For) {
            flag = flag && check((Stmt.For) stmt, environment);
        } else if(stmt instanceof Stmt.While) {
            flag = flag && check((Stmt.While) stmt, environment);
        } else {
            internalFailure("unknown statement encountered (" + stmt + ")", file.filename,stmt);
        }
        return flag;
    }

    public boolean check(Stmt.Assign stmt, Set<String> environment) {
        boolean flag = true;
        if(stmt.getLhs() instanceof Expr.Variable) {
            Expr.Variable var = (Expr.Variable) stmt.getLhs();
            environment.add(var.getName()); // we are initialising a new variable
        } else {
            flag = flag && check(stmt.getLhs(), environment);
        }

        flag = flag && check(stmt.getRhs(), environment);
        return flag;
    }

    public boolean check(Stmt.Print stmt, Set<String> environment) {
        boolean flag = true;
        flag = flag && check(stmt.getExpr(), environment);
        return flag;
    }

    public boolean check(Stmt.Return stmt, Set<String> environment) {
        check(stmt.getExpr(), environment);
        return false;
    }

    public boolean check(Stmt.VariableDeclaration stmt, Set<String> environment) {
        boolean flag = true;
        if(environment.contains(stmt.getName())) {
            syntaxError("variable already declared: " + stmt.getName(),
                    file.filename, stmt);
        } else if(stmt.getExpr() != null) {
            flag = flag && check(stmt.getExpr(), environment);
            environment.add(stmt.getName());
        }
        return flag;
    }

    public boolean check(Stmt.IfElse stmt, Set<String> environment) {
        check(stmt.getCondition(),environment); // check condition is valid
        HashSet<String> trueEnv = new HashSet<String>(environment);
        HashSet<String> falseEnv = new HashSet<String>(environment);
        boolean trueContinue = check(stmt.getTrueBranch(), trueEnv);   // check tru branch is valid
        boolean falseContinue = check(stmt.getFalseBranch(), falseEnv); // check false branch is valid

        // add all items defined on both branches to environment
        if (trueContinue && !falseContinue)
        {
            environment.addAll(trueEnv);
        }
        else if (!trueContinue && falseContinue)
        {
            environment.addAll(falseEnv);
        }
        else
        {
            for(String var : trueEnv) {
                if(falseEnv.contains(var)) {
                    environment.add(var);
                }
            }
        }
        if (!trueContinue && !falseContinue) return false;
        return true;
    }

    public boolean check(Stmt.For stmt, Set<String> environment) {
        boolean flag = true;
        flag = flag && check(stmt.getDeclaration(), environment);
        flag = flag && check(stmt.getCondition(), environment);
        flag = flag && check(stmt.getIncrement(), environment);
        flag = flag && check(stmt.getBody(), new HashSet<String>(environment));
        return flag;
    }

    public boolean check(Stmt.While stmt, Set<String> environment) {
        boolean flag = true;
        flag = flag && check(stmt.getCondition(), environment);
        flag = flag && check(stmt.getBody(), new HashSet<String>(environment));
        return flag;
    }

    /**
     * Check that all variables used in a given expression are definitely
     * assigned.
     *
     * @param expr
     *            The expression to check.
     * @param environment
     *            The set of variables which are definitely assigned.
     */
    public boolean check(Expr expr, Set<String> environment) {
        boolean flag = true;
        if (expr instanceof Expr.Binary) {
            flag = flag && check((Expr.Binary) expr, environment);
        } else if (expr instanceof Expr.Cast) {
            flag = flag && check((Expr.Cast) expr, environment);
        } else if (expr instanceof Expr.Constant) {
            flag = flag && check((Expr.Constant) expr, environment);
        } else if (expr instanceof Expr.IndexOf) {
            flag = flag && check((Expr.IndexOf) expr, environment);
        } else if (expr instanceof Expr.Invoke) {
            flag = flag && check((Expr.Invoke) expr, environment);
        } else if (expr instanceof Expr.ListConstructor) {
            flag = flag && check((Expr.ListConstructor) expr, environment);
        } else if (expr instanceof Expr.RecordAccess) {
            flag = flag && check((Expr.RecordAccess) expr, environment);
        } else if (expr instanceof Expr.RecordConstructor) {
            flag = flag && check((Expr.RecordConstructor) expr, environment);
        } else if (expr instanceof Expr.Unary) {
            flag = flag && check((Expr.Unary) expr, environment);
        } else if (expr instanceof Expr.Variable) {
            flag = flag && check((Expr.Variable) expr, environment);
        } else {
            internalFailure("unknown expression encountered (" + expr + ")",
                    file.filename, expr);
        }
        return flag;
    }

    public boolean check(Expr.Binary expr, Set<String> environment) {
        boolean flag = true;
        // if var check its in environment, otherwise check using whatever check method matches type
        if (expr.getLhs() instanceof Expr.Variable)
        {
            Expr.Variable var = (Expr.Variable) expr.getLhs();
            flag = flag && check(var, environment);
        }
        else {
            flag = flag && check(expr.getLhs(), environment);
        }
        if (expr.getRhs() instanceof Expr.Variable)
        {
            Expr.Variable var = (Expr.Variable) expr.getRhs();
            flag = flag && check(var, environment);
        }
        else {
            flag = flag && check(expr.getRhs(), environment);
        }
        return flag;

    }

    public boolean check(Expr.Cast expr, Set<String> environment) {
        boolean flag = true;
        // TODO: implement me!
        if (expr.getSource() instanceof Expr.Variable)
        {
            flag = flag && check((Expr.Variable) expr.getSource(), environment);
        }
        else
        {
            flag = flag && check(expr.getSource(), environment);
        }
        return flag;
    }

    public boolean check(Expr.Constant expr, Set<String> environment) {
        // Constants are obviousy already defined ;)
        return true;
    }

    public boolean check(Expr.IndexOf expr, Set<String> environment) {
        boolean flag = true;
        Expr source = expr.getSource();
        Expr index = expr.getIndex();
        if (source instanceof Expr.Variable)
        {
            flag = flag && check((Expr.Variable) source, environment);
        }
        else {
            flag = flag && check(source, environment);
        }

        if (index instanceof Expr.Variable)
        {
            flag = flag && check((Expr.Variable) index, environment);
        }
        else
        {
            flag = flag && check(index, environment);
        }

        return flag;

    }

    public boolean check(Expr.Invoke expr, Set<String> environment) {
        boolean flag = true;
        for (Expr arg : expr.getArguments())
        {
            if (arg instanceof Expr.Variable)
            {
                flag = flag && check((Expr.Variable) arg, environment);
            }
            else
            {
                flag = flag && check(arg, environment);
            }

        }
        return flag;

    }

    public boolean check(Expr.ListConstructor expr, Set<String> environment) {
        boolean flag = true;
        for (Expr arg : expr.getArguments())
        {
            if (arg instanceof Expr.Variable)
            {
                flag = flag && check((Expr.Variable) arg, environment);
            }
            else
            {
                flag = flag && check(arg, environment);
            }

        }
        return flag;
    }

    public boolean check(Expr.RecordAccess expr, Set<String> environment) {
        boolean flag = true;
        Expr source = expr.getSource();
        if (source instanceof Expr.Variable)
        {
            flag = flag && check((Expr.Variable) source, environment);
        }
        else {
            flag = flag && check(source, environment);
        }
        return flag;
    }

    public boolean check(Expr.RecordConstructor expr, Set<String> environment) {
        boolean flag = true;
        List<Pair<String, Expr>> pairs =  expr.getFields();
        for (Pair pair : pairs)
        {
            Expr ex = (Expr) pair.second();
            if (ex instanceof Expr.Variable)
            {
                flag = flag && check((Expr.Variable) ex, environment);
            }
            else
            {
                flag = flag && check(ex, environment);
            }
        }
        return flag;
    }

    public boolean check(Expr.Unary expr, Set<String> environment) {
        boolean flag = true;
        Expr ex = expr.getExpr();
        if (ex instanceof Expr.Variable)
        {
            flag = flag && check((Expr.Variable) ex, environment);
        }
        else {
            flag = flag && check(ex, environment);
        }
        return flag;
    }

    public boolean check(Expr.Variable expr, Set<String> environment) {
        if (!environment.contains(expr.getName())) {
            // This variable is not definitely assigned.
            syntaxError("variable " + expr.getName()
                    + " is not definitely assigned", file.filename, expr);
        }
        return true;

    }
}

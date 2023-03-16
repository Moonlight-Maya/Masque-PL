package language.parse;

import language.Lexer;
import language.bytecoderunner.LangFunction;
import language.compile.Bytecode;
import language.compile.Compiler;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static language.Lexer.TokenType.*;

public abstract class Expression {

    public final int startLine;

    protected Expression(int startLine) {
        this.startLine = startLine;
    }

    /**
     * Temporary method for tree-walk interpreter
     */

    public abstract void writeBytecode(Compiler compiler) throws Compiler.CompilationException;

    //Scans for declarations and emits bytecode to push null if it finds any, and register in compiler
    public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {}

    public static class BlockExpression extends Expression {
        public final List<Expression> exprs;
        public BlockExpression(int startLine, List<Expression> exprs) {
            super(startLine);
            this.exprs = exprs;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            compiler.beginScope();
            for (int i = 0; i < exprs.size(); i++) {
                exprs.get(i).scanForDeclarations(compiler);
                exprs.get(i).writeBytecode(compiler);
                if (i != exprs.size()-1)
                    compiler.bytecode(Bytecode.POP);
            }
            compiler.endScope();
        }
    }

    public static class IfExpression extends Expression {
        public final Expression condition, ifTrue, ifFalse;
        public IfExpression(int startLine, Expression condition, Expression ifTrue, Expression ifFalse) {
            super(startLine);
            this.condition = condition;
            this.ifTrue = ifTrue;
            this.ifFalse = ifFalse;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            condition.writeBytecode(compiler);

            int jumpElse = compiler.emitJump(Bytecode.JUMP_IF_FALSE);
            int jumpOut = -1;
            ifTrue.writeBytecode(compiler);

            if (ifFalse != null) //if we have an else statement, emit an unconditional jump to skip it
                jumpOut = compiler.emitJump(Bytecode.JUMP);

            //Always patch the jumpElse
            compiler.patchJump(jumpElse);

            if (ifFalse != null) {
                ifFalse.writeBytecode(compiler);
                compiler.patchJump(jumpOut);
            } else {
                compiler.bytecode(Bytecode.PUSH_NULL);
            }
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            condition.scanForDeclarations(compiler);
            ifTrue.scanForDeclarations(compiler);
            if (ifFalse != null)
                ifFalse.scanForDeclarations(compiler);
        }
    }

    public static class Literal extends Expression {
        public final Object value;
        public Literal(int startLine, Object value) {
            super(startLine);
            this.value = value;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            int loc = compiler.registerConstant(value);
            if (loc <= 255) {
                compiler.bytecodeWithByteArg(Bytecode.CONSTANT, (byte) loc);
            } else {
                throw new Compiler.CompilationException("Too many literals");
            }
        }
    }

    public static class Function extends Expression {
        public final List<String> paramNames;
        public final Expression body;

        public Function(int startLine, List<String> argNames, Expression body) {
            super(startLine);
            this.paramNames = argNames; this.body = body;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            Compiler thisCompiler = new Compiler(compiler);
            for (String param : paramNames)
                thisCompiler.registerLocal(param);
            body.writeBytecode(thisCompiler);
            String name = "function (line=" + startLine + ")";
            LangFunction f = thisCompiler.finish(name, paramNames.size());

            int idx = compiler.registerConstant(f);
            compiler.bytecodeWithByteArg(Bytecode.CONSTANT, (byte) idx);
        }
    }

    public static class Name extends Expression {
        public final String name;
        public Name(int startLine, String name) {
            super(startLine);
            this.name = name;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            int localIndex = compiler.indexOfLocal(name);
            if (localIndex != -1) {
                //If there's a local variable of this name in scope, then get local
                compiler.bytecodeWithByteArg(Bytecode.LOAD_LOCAL, (byte) localIndex);
            } else {
                //Otherwise , get global
                int loc = compiler.registerConstant(name);
                compiler.bytecodeWithByteArg(Bytecode.LOAD_GLOBAL, (byte) loc);
            }
        }
    }

    public static class This extends Name {
        public This(int startLine) {
            super(startLine, "this");
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            compiler.bytecodeWithByteArg(Bytecode.LOAD_LOCAL, (byte) 0);
        }
    }

    public static class Get extends Expression {
        public final Expression left;
        public final Expression index;
        public Get(int startLine, Expression left, Expression indexingName) {
            super(startLine);
            this.left = left; this.index = indexingName;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {

        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            left.scanForDeclarations(compiler);
            index.scanForDeclarations(compiler);
        }
    }

    public static class Set extends Expression {
        public final Expression left;
        public final Expression index;
        public final Expression right;
        public Set(int startLine, Expression left, Expression index, Expression right) {
            super(startLine);
            this.left = left; this.index = index; this.right = right;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {

        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            left.scanForDeclarations(compiler);
            index.scanForDeclarations(compiler);
            right.scanForDeclarations(compiler);
        }
    }

    public static class Call extends Expression {
        public final Expression callingObject; //May be null!
        public final List<Expression> args;
        public Call(int startLine, Expression left, List<Expression> args) {
            super(startLine);
            this.callingObject = left;
            this.args = args;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {

            if (callingObject instanceof Name n && n.name.equals("print")) {
                //temporary workaround for a print function
                args.get(0).writeBytecode(compiler);
                compiler.bytecode(Bytecode.PRINT);
                compiler.bytecode(Bytecode.PUSH_NULL);
            } else {
                callingObject.writeBytecode(compiler);
                for (Expression arg : args)
                    arg.writeBytecode(compiler);
                compiler.bytecodeWithByteArg(Bytecode.CALL, (byte) args.size());
            }
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException{
            callingObject.scanForDeclarations(compiler);
            for (Expression e : args)
                e.scanForDeclarations(compiler);
        }
    }

    public static class Assign extends Expression {
        public final String varName;
        public final Expression rhs;
        public boolean isGlobal = false;

        public Assign(int startLine, String varName, Expression rhs) {
            super(startLine);
            this.varName = varName; this.rhs = rhs;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            if (isGlobal) {
                int loc = compiler.registerConstant(varName);
                rhs.writeBytecode(compiler);
                compiler.bytecodeWithByteArg(Bytecode.SET_GLOBAL, (byte) loc);
            } else {
                int loc = compiler.indexOfLocal(varName);
                if (loc == -1) throw new Compiler.CompilationException("Shouldn't ever happen. indexOfLocal returned -1?");
                rhs.writeBytecode(compiler);
                compiler.bytecodeWithByteArg(Bytecode.SET_LOCAL, (byte) loc);
            }

        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            if (compiler.indexOfLocal(varName) == -1) {
                //emits a "declaration", the variable doesn't exist yet
                compiler.registerLocal(varName);
                compiler.bytecode(Bytecode.PUSH_NULL);
            }
            //Scan right side next
            rhs.scanForDeclarations(compiler);
        }
    }

    public static class Binary extends Expression {
        public final Expression left, right;
        public final Op op;

        public Binary(int startLine, Expression left, Op op, Expression right) {
            super(startLine);
            this.left = left; this.right = right; this.op = op;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            left.writeBytecode(compiler);
            right.writeBytecode(compiler);
            compiler.bytecode(op.bytecode);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            left.scanForDeclarations(compiler);
            right.scanForDeclarations(compiler);
        }

        public enum Op {
            ADD(PLUS, Bytecode.ADD),
            SUB(MINUS, Bytecode.SUB),

            MUL(TIMES, Bytecode.MUL),
            DIV(DIVIDE, Bytecode.DIV),
            MOD(MODULO, Bytecode.MOD),

            EQ(EQUALS, Bytecode.EQ),
            NEQ(NOT_EQUALS, Bytecode.NEQ),
            GT(GREATER, Bytecode.GT),
            GTE(GREATER_EQUAL, Bytecode.GTE),
            LT(LESS, Bytecode.LT),
            LTE(LESS_EQUAL, Bytecode.LTE);

            private final Lexer.TokenType t;
            private final byte bytecode;
            Op(Lexer.TokenType t, byte bytecode) {
                this.t = t;
                this.bytecode = bytecode;
            }

            private static final Map<Lexer.TokenType, Op> opMap = new EnumMap(Lexer.TokenType.class) {{
                for (Op op : Op.values())
                    put(op.t, op);
            }};

            public static Op get(Lexer.TokenType type) {
                return opMap.get(type);
            }
        }

    }

    public static class Unary extends Expression {
        public final Expression expr;
        public final Op op;

        public Unary(int startLine, Op op, Expression expr) {
            super(startLine);
            this.expr = expr; this.op = op;
        }

        @Override
        public void writeBytecode(Compiler compiler) throws Compiler.CompilationException {
            expr.writeBytecode(compiler);
            compiler.bytecode(op.bytecode);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            expr.scanForDeclarations(compiler);
        }

        public enum Op {
            NOT(Lexer.TokenType.NOT, (byte) 0),
            NEGATE(Lexer.TokenType.MINUS, (byte) 0);

            private final Lexer.TokenType t;
            private final byte bytecode;
            Op(Lexer.TokenType t, byte bytecode) {
                this.t = t;
                this.bytecode = bytecode;
            }

            private static final Map<Lexer.TokenType, Unary.Op> opMap = new EnumMap(Lexer.TokenType.class) {{
                for (Op op : Op.values())
                    put(op.t, op);
            }};

            public static Op get(Lexer.TokenType type) {
                return opMap.get(type);
            }
        }

    }



}

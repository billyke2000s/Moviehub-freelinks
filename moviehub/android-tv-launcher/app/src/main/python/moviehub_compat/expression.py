"""Evaluate only the literal/arithmetic syntax used by bundled text decoders."""
import ast
import operator

_BINARY = {ast.Add:operator.add, ast.Sub:operator.sub, ast.Mult:operator.mul,
           ast.Div:operator.truediv, ast.FloorDiv:operator.floordiv, ast.Mod:operator.mod,
           ast.BitOr:operator.or_, ast.BitAnd:operator.and_, ast.BitXor:operator.xor}
_UNARY = {ast.UAdd:operator.pos, ast.USub:operator.neg, ast.Invert:operator.invert}

def evaluate(text, variables=None):
    if len(text)>16384: raise ValueError('Decoder expression limit')
    tree=ast.parse(text,mode='eval')
    if sum(1 for _ in ast.walk(tree))>512: raise ValueError('Decoder expression limit')
    def visit(node,depth=0):
        if depth>32: raise ValueError('Decoder depth limit')
        if isinstance(node,ast.Constant) and type(node.value) in (int,float,str): result=node.value
        elif isinstance(node,ast.Name) and variables is not None and node.id in variables: result=variables[node.id]
        elif isinstance(node,ast.UnaryOp) and type(node.op) in _UNARY:
            result=_UNARY[type(node.op)](visit(node.operand,depth+1))
        elif isinstance(node,ast.BinOp) and type(node.op) in _BINARY:
            left,right=visit(node.left,depth+1),visit(node.right,depth+1)
            if isinstance(left,str) or isinstance(right,str):
                if not isinstance(node.op,ast.Add) or not isinstance(left,str) or not isinstance(right,str):
                    raise ValueError('Unsupported string expression')
                if len(left)+len(right)>65536: raise ValueError('Decoder string limit')
            result=_BINARY[type(node.op)](left,right)
        else: raise ValueError('Unsupported decoder expression')
        if isinstance(result,str) and len(result)>65536: raise ValueError('Decoder string limit')
        if isinstance(result,int) and result.bit_length()>256: raise ValueError('Decoder number limit')
        return result
    return visit(tree.body)

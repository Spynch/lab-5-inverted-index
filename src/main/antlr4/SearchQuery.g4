grammar SearchQuery;

@header {
package searchengine.query.antlr;
}

query
    : expression EOF
    ;

expression
    : orExpression
    ;

orExpression
    : andExpression (OR andExpression)*
    ;

andExpression
    : unaryExpression (AND unaryExpression)*
    ;

unaryExpression
    : NOT unaryExpression
    | positionalExpression
    ;

positionalExpression
    : primaryExpression ((ADJ | EDGE | NEAR) primaryExpression)*
    ;

primaryExpression
    : TERM
    | LPAREN expression RPAREN
    ;

AND: [Aa][Nn][Dd];
OR: [Oo][Rr];
NOT: [Nn][Oo][Tt];
ADJ: [Aa][Dd][Jj];
EDGE: [Ee][Dd][Gg][Ee];
NEAR: [Nn][Ee][Aa][Rr] '/' [0-9]+;
LPAREN: '(';
RPAREN: ')';
TERM: ~[ \t\r\n()]+;
WS: [ \t\r\n]+ -> skip;

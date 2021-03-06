package railo.runtime.db;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import railo.commons.lang.CFTypes;
import railo.commons.lang.StringUtil;
import railo.commons.math.MathUtil;
import railo.runtime.PageContext;
import railo.runtime.exp.DatabaseException;
import railo.runtime.exp.PageException;
import railo.runtime.op.Caster;
import railo.runtime.op.Operator;
import railo.runtime.sql.Select;
import railo.runtime.sql.SelectParser;
import railo.runtime.sql.Selects;
import railo.runtime.sql.exp.BracketExpression;
import railo.runtime.sql.exp.Column;
import railo.runtime.sql.exp.Expression;
import railo.runtime.sql.exp.op.Operation;
import railo.runtime.sql.exp.op.Operation1;
import railo.runtime.sql.exp.op.Operation2;
import railo.runtime.sql.exp.op.Operation3;
import railo.runtime.sql.exp.op.OperationN;
import railo.runtime.sql.exp.value.Value;
import railo.runtime.sql.exp.value.ValueNumber;
import railo.runtime.type.Array;
import railo.runtime.type.ArrayImpl;
import railo.runtime.type.Collection.Key;
import railo.runtime.type.Query;
import railo.runtime.type.QueryColumn;
import railo.runtime.type.QueryImpl;

/**
 * 
 */
public final class QoQ {





	public Query execute(PageContext pc,SQL sql,int maxrows) throws PageException {
		try {
			SelectParser parser=new SelectParser();
			Selects selects = parser.parse(sql.getSQLString());

			return execute(pc,sql,selects,maxrows);
		}
		catch(Throwable t) {
		    throw Caster.toPageException(t);
		}	
	}

	/**
	 * execute a SQL Statement against CFML Scopes
	 */
    public Query execute(PageContext pc,SQL sql, Selects selects,int maxrows) throws PageException {
    	Column[] orders=selects.getOrderbys();
    	Select[] arrSelects = selects.getSelects();
    	
    	
    	QueryImpl target=new QueryImpl(new String[0],0,"query");
		target.setSql(sql);
    	
    	for(int i=0;i<arrSelects.length;i++) {
    		arrSelects[i].getFroms();
    		Column[] froms = arrSelects[i].getFroms();
    		if(froms.length>1) throw new DatabaseException("can only work with single tables yet",null,sql,null);
    		executeSingle(pc,arrSelects[i],getSingleTable(pc, froms[0]),target,arrSelects.length>1?-1:maxrows,sql,
    				orders.length>0);
    	}
    	
	// Order By	
		if(orders.length>0) {
            
			for(int i=orders.length-1;i>=0;i--) {
				Column order = orders[i];
				target.sort(order.getColumn().toLowerCase(),order.isDirectionBackward()?Query.ORDER_DESC:Query.ORDER_ASC);
			}
			if(maxrows>-1) {
			    target.cutRowsTo(maxrows);
			}
		}
    // Distinct
        if(selects.isDistinct()) {
            String[] _keys=target.getColumns();
            QueryColumn[] columns=new QueryColumn[_keys.length];
            for(int i=0;i<columns.length;i++) {
                columns[i]=target.getColumn(_keys[i]);
            }
            
            int i;
            outer:for(int row=target.getRecordcount();row>1;row--) {
                for(i=0;i<columns.length;i++) {
                    if(!Operator.equals(columns[i].get(row),columns[i].get(row-1),true))
                        continue outer;
                }
                target.removeRow(row);
            }
        }
        return target;
	}
    


    private void executeSingle(PageContext pc, Select select, Query qr, QueryImpl target, int maxrows, SQL sql,boolean hasOrders) throws PageException {
    	ValueNumber oTop = select.getTop();
		if(oTop!=null) {
			int top=(int)oTop.getValueAsDouble();
			if(maxrows==-1 || maxrows>top) maxrows=top;
		}
		
    	
    	int recCount=qr.getRecordcount();
		Expression[] expSelects = select.getSelects();
		int selCount=expSelects.length;

		Map selects=new HashMap();
		Iterator<Key> it;
		Key k;
	// headers
		for(int i=0;i<selCount;i++) {
			Expression expSelect = expSelects[i];
			
			if(expSelect.getAlias().equals("*")) {
				it = qr.keyIterator();
				while(it.hasNext()){
					k = it.next();
					selects.put(k.getLowerString(),k.getLowerString());
					queryAddColumn(target,k.getLowerString());
				}
			}
			else {
				String alias=expSelect.getAlias();
				alias=alias.toLowerCase();
				
				selects.put(alias,expSelect);
				queryAddColumn(target,alias);
			}
		}
		String[] headers = (String[])selects.keySet().toArray(new String[selects.size()]);
		//QueryImpl rtn=new QueryImpl(headers,0,"query");
		//rtn.setSql(sql);
		
	// loop records
		//Column[] orders = select.getOrderbys();
		Operation where = select.getWhere();
		
		boolean hasMaxrow=maxrows>-1 && !hasOrders;
		
		// get target columns
		QueryColumn[] trgColumns=new QueryColumn[headers.length];
		Object[] trgValues=new Object[headers.length];
		for(int cell=0;cell<headers.length;cell++){
			trgColumns[cell]=target.getColumn(headers[cell]);
			trgValues[cell]=selects.get(headers[cell]);
		}
		
		for(int row=1;row<=recCount;row++) {
		    sql.setPosition(0);
			if(hasMaxrow && maxrows<=target.getRecordcount())break;
		    boolean useRow=where==null || Caster.toBooleanValue(executeExp(pc,sql,qr, where, row));
		    if(useRow) {
			    target.addRow(1);
			    for(int cell=0;cell<headers.length;cell++){
					//Object value = selects.get(headers[cell]);
					trgColumns[cell].set(target.getRecordcount(), getValue(pc,sql,qr,row,headers[cell],trgValues[cell]));
						/*target.setAt(
							headers[cell],
							target.getRecordcount(),
							getValue(pc,sql,qr,row,headers[cell],trgValues[cell])
						);*/
				}
			}
		}

	// Group By	
	if(select.getGroupbys().length>0)
		throw new DatabaseException("group by are not supported at the moment",null,sql,null);
	if(select.getHaving()!=null)
		throw new DatabaseException("having is not supported at the moment",null,sql,null);
		
	}

	private void queryAddColumn(QueryImpl query, String column) throws DatabaseException {
		if(!query.containsKey(column)) {
			query.addColumn(column, new ArrayImpl());
		}
	}

	private Array array(String value, int recordcount) {
		Array array = new ArrayImpl();
		if(recordcount==0) return array;
		for(int i=0;i<recordcount;i++) {
			array.appendEL(value);
		}
		return array;
	}

	/*private QueryImpl execute2(PageContext pc,SQL sql, Query qr, Select select,Column[] orders,int maxrows) throws PageException {
		
        int recCount=qr.getRecordcount();
		Expression[] expSelects = select.getSelects();
		int selCount=expSelects.length;
		
		Map selects=new HashTable();
		boolean isSMS=false;
		Key[] keys;
	// headers
		for(int i=0;i<selCount;i++) {
			Expression expSelect = expSelects[i];
			
			if(expSelect.getAlias().equals("*")) {
				
				keys = qr.keys();
				for(int y=0;y<keys.length;y++){
					selects.put(keys[y].getLowerString(),keys[y].getLowerString());
				}
			}
			else {
				String alias=expSelect.getAlias();
				alias=alias.toLowerCase();
				
				selects.put(alias,expSelect);
			}
		}
		String[] headers = (String[])selects.keySet().toArray(new String[selects.size()]);
		
		QueryImpl rtn=new QueryImpl(headers,0,"query");
		rtn.setSql(sql);
		
	// loop records
		Operation where = select.getWhere();
		
		boolean hasMaxrow=maxrows>-1 && (orders==null || orders.length==0);
		for(int row=1;row<=recCount;row++) {
		    sql.setPosition(0);
			if(hasMaxrow && maxrows<=rtn.getRecordcount())break;
		    boolean useRow=where==null || Caster.toBooleanValue(executeExp(pc,sql,qr, where, row));
			if(useRow) {
			    
				rtn.addRow(1);
				for(int cell=0;cell<headers.length;cell++){
					Object value = selects.get(headers[cell]);

						rtn.setAt(
							headers[cell],
							rtn.getRecordcount(),
							getValue(pc,sql,qr,row,headers[cell],value)
						);
				}
			}
		}

	// Group By	
	if(select.getGroupbys().length>0)
		throw new DatabaseException("group by are not supported at the moment",null,sql);
	if(select.getHaving()!=null)
		throw new DatabaseException("having is not supported at the moment",null,sql);
		
	// Order By	
		if(orders.length>0) {
            
			for(int i=orders.length-1;i>=0;i--) {
				Column order = orders[i];
				rtn.sort(order.getColumn().toLowerCase(),order.isDirectionBackward()?Query.ORDER_DESC:Query.ORDER_ASC);
			}
			if(maxrows>-1) {
			    rtn.cutRowsTo(maxrows);
			}
		}
    // Distinct
        if(select.isDistinct()) {
            String[] _keys=rtn.getColumns();
            QueryColumn[] columns=new QueryColumn[_keys.length];
            for(int i=0;i<columns.length;i++) {
                columns[i]=rtn.getColumn(_keys[i]);
            }
            
            int i;
            outer:for(int row=rtn.getRecordcount();row>1;row--) {
                for(i=0;i<columns.length;i++) {
                    if(!Operator.equals(columns[i].get(row),columns[i].get(row-1),true))
                        continue outer;
                }
                rtn.removeRow(row);
            }
        }
		return rtn;
	}*/
	
	/**
	 * return value
	 * @param sql
	 * @param querySource
	 * @param row
	 * @param key
	 * @param value
	 * @return value
	 * @throws PageException
	 */
	private Object getValue(PageContext pc,SQL sql,Query querySource, int row, String key, Object value) throws PageException {
		if(value instanceof Expression)return executeExp(pc,sql,querySource, ((Expression)value),row);
		return querySource.getAt(key,row);
	}

	/**
	 * @param pc Page Context of the Request
	 * @param query ZQLQuery
	 * @return Railo Query
	 * @throws PageException
	 */
	private Query getSingleTable(PageContext pc, Column table) throws PageException {
		return Caster.toQuery(pc.getVariable(table.getFullName()));
	}
	

	/**
	 * Executes a ZEXp
	 * @param sql
	 * @param qr Query Result
	 * @param exp expression to execute
	 * @param row current row of resultset
	 * @return result
	 * @throws PageException
	 */
	private Object executeExp(PageContext pc,SQL sql,Query qr, Expression exp, int row) throws PageException {
		//print.e("name:"+exp.getClass().getName());
		if(exp instanceof Value) return ((Value)exp).getValue();//executeConstant(sql,qr, (Value)exp, row);
		if(exp instanceof Column) return executeColumn(sql,qr, (Column)exp, row);
		if(exp instanceof Operation) return executeOperation(pc,sql,qr, (Operation)exp, row);
		if(exp instanceof BracketExpression) return executeBracked(pc,sql,qr, (BracketExpression)exp, row);
		throw new DatabaseException("unsupported sql statement ["+exp+"]",null,sql,null);
	}
	
	private Object executeExp(PageContext pc,SQL sql,Query qr, Expression exp, int row, Object columnDefault) throws PageException {
		//print.o(exp.getClass().getName());
		if(exp instanceof Value) return ((Value)exp).getValue();//executeConstant(sql,qr, (Value)exp, row);
		if(exp instanceof Column) return executeColumn(sql,qr, (Column)exp, row,columnDefault);
		if(exp instanceof Operation) return executeOperation(pc,sql,qr, (Operation)exp, row);
		if(exp instanceof BracketExpression) return executeBracked(pc,sql,qr, (BracketExpression)exp, row);
		throw new DatabaseException("unsupported sql statement ["+exp+"]",null,sql,null);
	}

	private Object executeOperation(PageContext pc,SQL sql,Query qr, Operation operation, int row) throws PageException {
		
		if(operation instanceof Operation2) {
			Operation2 op2=(Operation2) operation;
			
			switch(op2.getOperator()){
			case Operation.OPERATION2_AND:		return executeAnd(pc,sql,qr,op2,row);
			case Operation.OPERATION2_OR:		return executeOr(pc,sql,qr,op2,row);
			case Operation.OPERATION2_XOR:		return executeXor(pc,sql,qr,op2,row);
			case Operation.OPERATION2_EQ:		return executeEQ(pc,sql,qr,op2,row);
			case Operation.OPERATION2_NEQ:		return executeNEQ(pc,sql,qr,op2,row);
			case Operation.OPERATION2_LTGT:		return executeNEQ(pc,sql,qr,op2,row);
			case Operation.OPERATION2_LT:		return executeLT(pc,sql,qr,op2,row);
			case Operation.OPERATION2_LTE:		return executeLTE(pc,sql,qr,op2,row);
			case Operation.OPERATION2_GT:		return executeGT(pc,sql,qr,op2,row);
			case Operation.OPERATION2_GTE:		return executeGTE(pc,sql,qr,op2,row);
			case Operation.OPERATION2_MINUS:	return executeMinus(pc,sql,qr,op2,row);
			case Operation.OPERATION2_PLUS:		return executePlus(pc,sql,qr,op2,row);
			case Operation.OPERATION2_DIVIDE:	return executeDivide(pc,sql,qr,op2,row);
			case Operation.OPERATION2_MULTIPLY:	return executeMultiply(pc,sql,qr,op2,row);
			case Operation.OPERATION2_EXP:		return executeExponent(pc,sql,qr,op2,row);
			case Operation.OPERATION2_LIKE:		return Caster.toBoolean(executeLike(pc,sql,qr,op2,row));
			case Operation.OPERATION2_NOT_LIKE:	return Caster.toBoolean(!executeLike(pc,sql,qr,op2,row));
			case Operation.OPERATION2_MOD:		return executeMod(pc,sql,qr,op2,row);
			}
				
				
		}

		if(operation instanceof Operation1) {
			Operation1 op1=(Operation1) operation;
			int o = op1.getOperator();
			
			if(o==Operation.OPERATION1_IS_NULL)	{
				Object value = executeExp( pc,sql,qr,op1.getExp(),row,null);
				return Caster.toBoolean(value==null);
			}
			if(o==Operation.OPERATION1_IS_NOT_NULL) {
				Object value = executeExp( pc,sql,qr,op1.getExp(),row,null);
				return Caster.toBoolean(value!=null);
			}
			
			Object value = executeExp( pc,sql,qr,op1.getExp(),row);
			
			if(o==Operation.OPERATION1_MINUS) 	return Caster.toDouble(-Caster.toDoubleValue(value));
			if(o==Operation.OPERATION1_PLUS) 	return Caster.toDouble(value);
			if(o==Operation.OPERATION1_NOT) 	return Caster.toBoolean(!Caster.toBooleanValue(value));
			
		}

		if(operation instanceof Operation3) {
			Operation3 op3=(Operation3) operation;
			int o = op3.getOperator();
			if(o==Operation.OPERATION3_BETWEEN) return executeBetween(pc,sql,qr,op3,row);
			if(o==Operation.OPERATION3_LIKE) return executeLike(pc,sql,qr,op3,row);
		}
		
		if(!(operation instanceof OperationN)) throw new DatabaseException("invalid syntax for SQL Statement",null,sql,null);
		
		OperationN opn=(OperationN) operation;
		
		String op=StringUtil.toLowerCase(opn.getOperator());
		Expression[] operators = opn.getOperants();
		
		/*if(count==0 && op.equals("?")) {
		    int pos=sql.getPosition(); 
		    if(sql.getItems().length<=pos) throw new DatabaseException("invalid syntax for SQL Statement",null,sql);
		    sql.setPosition(pos+1);
		    return sql.getItems()[pos].getValueForCF();
		}*/
        // 11111111111111111111111111111111111111111111111111111
        if(operators.length==1) {
            Object value = executeExp( pc,sql,qr,operators[0],row);
            
            // Functions
            switch(op.charAt(0)) {
            case 'a':
                if(op.equals("abs"))    return new Double(MathUtil.abs(Caster.toDoubleValue(value)));
                if(op.equals("acos"))   return new Double(Math.acos(Caster.toDoubleValue(value)));
                if(op.equals("asin"))   return new Double(Math.asin(Caster.toDoubleValue(value)));
                if(op.equals("atan"))   return new Double(Math.atan(Caster.toDoubleValue(value)));
            break;
            case 'c':
                if(op.equals("ceiling"))return new Double(Math.ceil(Caster.toDoubleValue(value)));
                if(op.equals("cos"))    return new Double(Math.cos(Caster.toDoubleValue(value)));
                if(op.equals("cast"))    return Caster.castTo(pc, CFTypes.toShort(operators[0].getAlias(),CFTypes.TYPE_UNKNOW),operators[0].getAlias(), value);
            break;
            case 'e':
                if(op.equals("exp"))    return new Double(Math.exp(Caster.toDoubleValue(value)));
            break;
            case 'f':
                if(op.equals("floor"))  return new Double(Math.floor(Caster.toDoubleValue(value)));
            break;
            case 'u':
                if(op.equals("upper") || op.equals("ucase")) return Caster.toString(value).toUpperCase();
            break;
            
            case 'l':
                if(op.equals("lower")|| op.equals("lcase")) return Caster.toString(value).toLowerCase();
                if(op.equals("ltrim"))  return StringUtil.ltrim(Caster.toString(value),null);
                if(op.equals("length")) return new Double(Caster.toString(value).length());
            break;
            case 'r':
                if(op.equals("rtrim"))  return StringUtil.rtrim(Caster.toString(value),null);
            break;
            case 's':
                if(op.equals("sign"))   return new Double(MathUtil.sgn(Caster.toDoubleValue(value)));
                if(op.equals("sin"))    return new Double(Math.sin(Caster.toDoubleValue(value)));
                if(op.equals("soundex"))return StringUtil.soundex(Caster.toString(value));
                if(op.equals("sin"))    return new Double(Math.sqrt(Caster.toDoubleValue(value)));
            break;
            case 't':
                if(op.equals("tan"))    return new Double(Math.tan(Caster.toDoubleValue(value)));
                if(op.equals("trim"))   return Caster.toString(value).trim();
            break;
            }
            
        }
        
        // 22222222222222222222222222222222222222222222222222222
		else if(operators.length==2) {
			
			//if(op.equals("=") || op.equals("in")) return executeEQ(pc,sql,qr,expression,row);
			
            Object left = executeExp(pc,sql,qr,operators[0],row);
            Object right = executeExp(pc,sql,qr,operators[1],row);
            
			// Functions
            switch(op.charAt(0)) {
            case 'a':
                if(op.equals("atan2"))
                    return new Double(Math.atan2(Caster.toDoubleValue(left),Caster.toDoubleValue(right)));
            break;
            case 'b':
                if(op.equals("bitand"))
                    return new Double(Operator.bitand(Caster.toDoubleValue(left),Caster.toDoubleValue(right)));
                if(op.equals("bitor"))
                    return new Double(Operator.bitor(Caster.toDoubleValue(left),Caster.toDoubleValue(right)));
            break;
            case 'c':
                if(op.equals("concat"))
                    return Caster.toString(left).concat(Caster.toString(right));
            break;
            case 'm':
                if(op.equals("mod"))
                    return new Double(Operator.modulus(Caster.toDoubleValue(left),Caster.toDoubleValue(right)));
            break;
            }
                
			//throw new DatabaseException("unsopprted sql statement ["+op+"]",null,sql);
		}
        // 3333333333333333333333333333333333333333333333333333333333333333333
		
        if(op.equals("in")) return executeIn(pc,sql,qr,opn,row,false);
        if(op.equals("not_in")) return executeIn(pc,sql,qr,opn,row,true);
		
        
        /*
        
        addCustomFunction("cot",1);
        addCustomFunction("degrees",1);
        addCustomFunction("log",1);
        addCustomFunction("log10",1);

        addCustomFunction("pi",0);
        addCustomFunction("power",2);
        addCustomFunction("radians",1);
        addCustomFunction("rand",0);
        addCustomFunction("round",2);
        addCustomFunction("roundmagic",1);
        addCustomFunction("truncate",2);
        addCustomFunction("ascii",1);
        addCustomFunction("bit_length",1);
        addCustomFunction("char",1);
        addCustomFunction("char_length",1);
        addCustomFunction("difference",2);
        addCustomFunction("hextoraw",1);
        addCustomFunction("insert",4);
        addCustomFunction("left",2);
        addCustomFunction("locate",3);
        addCustomFunction("octet_length",1);
        addCustomFunction("rawtohex",1);
        addCustomFunction("repeat",2);
        addCustomFunction("replace",3);
        addCustomFunction("right",2);
        addCustomFunction("space",1);
        addCustomFunction("substr",3);
        addCustomFunction("substring",3);
        addCustomFunction("curdate",0);
        addCustomFunction("curtime",0);
        addCustomFunction("datediff",3);
        addCustomFunction("dayname",1);
        addCustomFunction("dayofmonth",1);
        addCustomFunction("dayofweek",1);
        addCustomFunction("dayofyear",1);
        addCustomFunction("hour",1);
        addCustomFunction("minute",1);
        addCustomFunction("month",1);
        addCustomFunction("monthname",1);
        addCustomFunction("now",0);
        addCustomFunction("quarter",1);
        addCustomFunction("second",1);
        addCustomFunction("week",1);
        addCustomFunction("year",1);
        addCustomFunction("current_date",1);
        addCustomFunction("current_time",1);
        addCustomFunction("current_timestamp",1);
        addCustomFunction("database",0);
        addCustomFunction("user",0);
        addCustomFunction("current_user",0);
        addCustomFunction("identity",0);
        addCustomFunction("ifnull",2);
        addCustomFunction("casewhen",3);
        addCustomFunction("convert",2);
        //addCustomFunction("cast",1);
        addCustomFunction("coalesce",1000);
        addCustomFunction("nullif",2);
        addCustomFunction("extract",1);
        addCustomFunction("position",1);
        */
        
		//print(expression);
		throw new DatabaseException(
				"unsopprted sql statement ("+op+") ",null,sql,null);

	}

	/* *
	 * @param expression
	 * /
	private void print(ZExpression expression) {
		print.ln("Operator:"+expression.getOperator().toLowerCase());
		int len=expression.nbOperands();
		for(int i=0;i<len;i++) {
			print.ln("	["+i+"]=	"+expression.getOperand(i));
		}
	}/*
	
	

	/**
	 * 
	 * execute a and operation
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return
	 * @throws PageException
	 */
	private Object executeAnd(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		//print.out("("+expression.getLeft().toString(true)+" AND "+expression.getRight().toString(true)+")");
		boolean rtn=Caster.toBooleanValue(executeExp(pc,sql,qr,expression.getLeft(),row));
		if(!rtn) return Boolean.FALSE;
		return Caster.toBoolean(executeExp(pc,sql,qr,expression.getRight(),row));
	}
	

	
	private Object executeBracked(PageContext pc, SQL sql, Query qr, BracketExpression expression, int row) throws PageException {
		return executeExp(pc,sql,qr,expression.getExp(),row);
	}
	
	/**
	 * 
	 * execute a and operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeOr(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		//print.out("("+expression.getLeft().toString(true)+" OR "+expression.getRight().toString(true)+")");
		boolean rtn=Caster.toBooleanValue(executeExp(pc,sql,qr,expression.getLeft(),row));
		if(rtn) return Boolean.TRUE;
		Boolean rtn2 = Caster.toBoolean(executeExp(pc,sql,qr,expression.getRight(),row));
		
		//print.out(rtn+ " or "+rtn2);
		
		return rtn2;
		
	}
	
	private Object executeXor(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return 
	    	Caster.toBooleanValue(executeExp(pc,sql,qr,expression.getLeft(),row)) 
	    	^ 
	    	Caster.toBooleanValue(executeExp(pc,sql,qr,expression.getRight(),row))?
	    			Boolean.TRUE:Boolean.FALSE;
	}
	
	

	
	/**
	 * 
	 * execute a equal operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeEQ(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {	
		return (executeCompare(pc,sql,qr, expression, row)==0)?Boolean.TRUE:Boolean.FALSE;
	}

	/**
	 * 
	 * execute a not equal operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeNEQ(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return (executeCompare(pc,sql,qr, expression, row)!=0)?Boolean.TRUE:Boolean.FALSE;
	}

	/**
	 * 
	 * execute a less than operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeLT(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return (executeCompare(pc,sql,qr, expression, row)<0)?Boolean.TRUE:Boolean.FALSE;
	}

	/**
	 * 
	 * execute a less than or equal operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeLTE(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return (executeCompare(pc,sql,qr, expression, row)<=0)?Boolean.TRUE:Boolean.FALSE;
	}

	/**
	 * 
	 * execute a greater than operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeGT(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return (executeCompare(pc,sql,qr, expression, row)>0)?Boolean.TRUE:Boolean.FALSE;
	}

	/**
	 * 
	 * execute a greater than or equal operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeGTE(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return (executeCompare(pc,sql,qr, expression, row)>=0)?Boolean.TRUE:Boolean.FALSE;
	}
	
	/**
	 * 
	 * execute a equal operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private int executeCompare(PageContext pc,SQL sql,Query qr, Operation2 op, int row) throws PageException {
		//print.e(op.getLeft().getClass().getName());
		return 
			Operator.compare(executeExp(pc,sql,qr,op.getLeft(),row),executeExp(pc,sql,qr,op.getRight(),row));
	}

	
	
	private Object executeMod(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		
		return Caster.toDouble(
				Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getLeft(),row))%
				Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getRight(),row)));
	}

	/**
	 * 
	 * execute a greater than or equal operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Boolean executeIn(PageContext pc,SQL sql,Query qr, OperationN expression, int row, boolean isNot) throws PageException {
		Expression[] operators = expression.getOperants();
		Object left=executeExp(pc,sql,qr,operators[0],row);
		
		for(int i=1;i<operators.length;i++) {
			if(Operator.compare(left,executeExp(pc,sql,qr,operators[i],row))==0) 
				return isNot?Boolean.FALSE:Boolean.TRUE;
		}
		return isNot?Boolean.TRUE:Boolean.FALSE;
	}
	
	/**
	 * 
	 * execute a minus operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeMinus(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return 
		new Double(
			Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getLeft(),row))
			-
			Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getRight(),row))
		);
	}
	
	/**
	 * 
	 * execute a divide operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeDivide(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return 
		new Double(
			Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getLeft(),row))
			/
			Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getRight(),row))
		);
	}
	
	/**
	 * 
	 * execute a multiply operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeMultiply(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return 
		new Double(
			Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getLeft(),row))
			*
			Caster.toDoubleValue(executeExp(pc,sql,qr,expression.getRight(),row))
		);
	}
	
	/**
	 * 
	 * execute a multiply operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result 
	 * @throws PageException
	 */
	private Object executeExponent(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return 
		Integer.valueOf(
			Caster.toIntValue(executeExp(pc,sql,qr,expression.getLeft(),row))
			^
			Caster.toIntValue(executeExp(pc,sql,qr,expression.getRight(),row))
		);
	}
	
	/**
	 * 
	 * execute a plus operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executePlus(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		Object left=executeExp(pc,sql,qr,expression.getLeft(),row);
		Object right=executeExp(pc,sql,qr,expression.getRight(),row);
		
		try {
			return new Double(Caster.toDoubleValue(left)+Caster.toDoubleValue(right));
		} catch (PageException e) {
			return Caster.toString(left)+Caster.toString(right);
		} 
	}
	
	/**
	 * 
	 * execute a between operation
	 * @param sql
	 * @param qr QueryResult to execute on it
	 * @param expression
	 * @param row row of resultset to execute
	 * @return result
	 * @throws PageException
	 */
	private Object executeBetween(PageContext pc,SQL sql,Query qr, Operation3 expression, int row) throws PageException {
		Object left=executeExp(pc,sql,qr,expression.getExp(),row);
		Object right1=executeExp(pc,sql,qr,expression.getLeft(),row);
		Object right2=executeExp(pc,sql,qr,expression.getRight(),row);
		//print.out(left+" between "+right1+" and "+right2
		//		+" = "+((Operator.compare(left,right1)>=0)+" && "+(Operator.compare(left,right2)<=0)));
		
		return (
			(Operator.compare(left,right1)>=0)
			&&
			(Operator.compare(left,right2)<=0)
		)?Boolean.TRUE:Boolean.FALSE;
	}

	private Object executeLike(PageContext pc,SQL sql,Query qr, Operation3 expression, int row) throws PageException {
		return LikeCompare.like(sql,
				Caster.toString(executeExp(pc,sql,qr,expression.getExp(),row)),
				Caster.toString(executeExp(pc,sql,qr,expression.getLeft(),row)),
				Caster.toString(executeExp(pc,sql,qr,expression.getRight(),row)))?Boolean.TRUE:Boolean.FALSE;
	}
	
	private boolean executeLike(PageContext pc,SQL sql,Query qr, Operation2 expression, int row) throws PageException {
		return LikeCompare.like(sql,
				Caster.toString(executeExp(pc,sql,qr,expression.getLeft(),row)),
				Caster.toString(executeExp(pc,sql,qr,expression.getRight(),row)));
	}

	/**
	 * Executes a constant value
	 * @param sql 
	 * @param qr
	 * @param constant
	 * @param row
	 * @return result
	 * @throws PageException
	 */
	private Object executeColumn(SQL sql,Query qr, Column column, int row) throws PageException {
		if(column.getColumn().equals("?")) {
		    int pos=column.getColumnIndex();
		    if(sql.getItems().length<=pos) throw new DatabaseException("invalid syntax for SQL Statement",null,sql,null);
		    return sql.getItems()[pos].getValueForCF();
		}
		return column.getValue(qr, row);
		//return qr.getAt(column.getColumn(),row);	
	}
	
	private Object executeColumn(SQL sql,Query qr, Column column, int row, Object defaultValue) throws PageException {
	    if(column.getColumn().equals("?")) {
		    int pos=column.getColumnIndex();
		    if(sql.getItems().length<=pos) throw new DatabaseException("invalid syntax for SQL Statement",null,sql,null);
		    return sql.getItems()[pos].getValueForCF();
		}
		return column.getValue(qr, row,defaultValue);	
	}
}

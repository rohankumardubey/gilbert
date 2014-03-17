/**
 * gilbert - Distributed Linear Algebra on Sparse Matrices
 * Copyright (C) 2013  Sebastian Schelter, Till Rohrmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.gilbertlang.language.typer

import org.gilbertlang.language.definition.Types._
import org.gilbertlang.language.definition.IntermediateAbstractSyntaxTree._
import org.gilbertlang.language.definition.TypedAbstractSyntaxTree._
import org.gilbertlang.language.definition.Values._
import org.gilbertlang.language.definition.AbstractSyntaxTree._
import org.gilbertlang.language.definition.{BuiltinOperators, Values, Types, BuiltinSymbols}
import org.gilbertlang.language.definition.Values.ValueVar
import scala.Some
import org.gilbertlang.language.definition.Types.NumericTypeVar
import org.gilbertlang.language.definition.Values.ReferenceValue
import org.gilbertlang.language.definition.IntermediateAbstractSyntaxTree.IntermediateIdentifier
import org.gilbertlang.language.definition.Types.PolymorphicType
import org.gilbertlang.language.definition.Types.TypeVar
import org.gilbertlang.language.definition.IntermediateAbstractSyntaxTree.IntermediateInteger
import org.gilbertlang.language.definition.Values.IntValue
import org.gilbertlang.language.definition.IntermediateAbstractSyntaxTree.IntermediateProgram
import org.gilbertlang.language.definition.Types.UniversalType
import org.gilbertlang.language.definition.Values.UniversalValue
import org.gilbertlang.language.definition.Types.MatrixType
import org.gilbertlang.language.definition.Operators._
import scala.language.postfixOps

import Types.Helper._
import Values.Helper._

class Typer(private val typeEnvironment: scala.collection.mutable.Map[String, Type],
            private val valueEnvironment: scala.collection.mutable.Map[String, IntermediateExpression],
             private val typeVarMapping: scala.collection.mutable.Map[Type, Type],
             private val valueVarMapping: scala.collection.mutable.Map[Value, Value]) {

  def this() = this(scala.collection.mutable.Map[String, Type](),
    scala.collection.mutable.Map[String, IntermediateExpression](),
    scala.collection.mutable.Map[Type, Type](),
    scala.collection.mutable.Map[Value, Value]())

  def finalizeTyping(program: IntermediateProgram): TypedProgram = {
    TypedProgram(program.statementsOrFunctions map { finalizeTyping })
  }

  def finalizeTyping( stmtOrFunc: IntermediateStatementOrFunction): TypedStatementOrFunction = {
    stmtOrFunc match {
      case stmt: IntermediateStatement => finalizeTyping(stmt)
      case func: IntermediateFunction => finalizeTyping(func)
    }
  }

  def finalizeTyping(func: IntermediateFunction): TypedFunction = {
    TypedFunction(func.values map { finalizeTyping }, finalizeTyping(func.identifier),
      func.parameters map { finalizeTyping }, finalizeTyping(func.body))
  }

  def finalizeTyping(stmt: IntermediateStatement): TypedStatement = {
    stmt match {
      case IntermediateNOP => TypedNOP
      case IntermediateOutputResultStatement(stmtWithResult) => TypedOutputResultStatement(finalizeTyping
        (stmtWithResult))
      case stmtWithResult: IntermediateStatementWithResult => finalizeTyping(stmtWithResult)
    }
  }

  def finalizeTyping(stmtWithResult: IntermediateStatementWithResult): TypedStatementWithResult = {
    stmtWithResult match {
      case IntermediateAssignment(id, exp) => TypedAssignment(finalizeTyping(id), finalizeTyping(exp))
      case expression: IntermediateExpression => finalizeTyping(expression)
    }
  }

  def finalizeTyping(functionExpression: IntermediateFunctionExpression): TypedFunctionExpression = {
    functionExpression match {
      case x: IntermediateIdentifier => finalizeTyping(x)
      case IntermediateFunctionReference(func, datatype) =>
        TypedFunctionReference(finalizeTyping(func), resolveType(datatype))
      case IntermediateAnonymousFunction(args, body, closure, datatype) =>
        TypedAnonymousFunction(args map {finalizeTyping(_)}, finalizeTyping(body),
          closure, resolveType(datatype))
    }
  }

  def finalizeTyping(expression: IntermediateExpression): TypedExpression = {
    expression match {
      case x:IntermediateIdentifier => finalizeTyping(x)
      case x: IntermediateFunctionExpression => finalizeTyping(x)
      case IntermediateInteger(value) => TypedInteger(value)
      case IntermediateFloatingPoint(value) => TypedFloatingPoint(value)
      case IntermediateString(value) => TypedString(value)
      case IntermediateBoolean(value) => TypedBoolean(value)
      case IntermediateMatrix(rows, datatype) =>
        val resolvedRows = rows map { elements => TypedMatrixRow(elements.value map {finalizeTyping(_) })}
        TypedMatrix(resolvedRows, resolveType(datatype).asInstanceOf[MatrixType])
      case IntermediateBinaryExpression(a, aType, op, b, bType, datatype) => TypedBinaryExpression(typeConversion
        (finalizeTyping(a), aType), op, typeConversion(finalizeTyping(b), bType),resolveType(datatype))
      case IntermediateUnaryExpression(a, aType, op, datatype) => TypedUnaryExpression(typeConversion(finalizeTyping
        (a),aType), op, resolveType(datatype))
      case IntermediateFunctionApplication(fun, args, paramsType, datatype) =>
        val resolvedArgs = args map { finalizeTyping(_) }
        val typeConvertedArgs = resolvedArgs zip paramsType map { case (arg, tpe) => typeConversion(arg, tpe)}
        TypedFunctionApplication(finalizeTyping(fun), typeConvertedArgs, resolveType(datatype))
      case IntermediateCellArray(elements, datatype) => TypedCellArray(elements map {finalizeTyping},
        resolveType(datatype))
      case IntermediateCellArrayIndexing(cellArray, index, datatype) => TypedCellArrayIndexing(finalizeTyping
        (cellArray), index , resolveType(datatype))
    }
  }
  
  def finalizeTyping(id: IntermediateIdentifier): TypedIdentifier = {
    TypedIdentifier(id.value, resolveType(id.datatype))
  }

  def resolveType(datatype: Type): Type = {
    datatype match {
      case _: AbstractTypeVar => {
        var result: Type = datatype

        while (result != typeVarMapping.getOrElse(result, result)) {
          result = typeVarMapping(result)
        }

        result match {
          case _: AbstractTypeVar => result
          case _ => resolveType(result)
        }
      }
      case MatrixType(elementType, rowValue, colValue) => {
        MatrixType(resolveType(elementType), resolveValue(rowValue), resolveValue(colValue))
      }
      case FunctionType(args, result) => FunctionType(args map { resolveType }, resolveType(result))
      case PolymorphicType(types) => PolymorphicType(types map { resolveType })
      case ConcreteCellArrayType(types) => ConcreteCellArrayType( types map { resolveType })
      case x:InterimCellArrayType =>
        val resolvedTypes = x.types map { resolveType }
        x.types = resolvedTypes
        x
      case x => x
    }
  }

  def simplifyValue(value: Value) = value

  def resolveValue(value: Value): Value = {
    val resolvedValue = value match {
      case _: ValueVar => {
        var result: Value = value

        while (result != valueVarMapping.getOrElse(result, result)) {
          result = valueVarMapping(result)
        }

        result
      }
      case _ => value
    }

    simplifyValue(resolvedValue)
  }

  def updateTypeVarMapping(typeVar: AbstractTypeVar, datatype: Type) {
    typeVarMapping.update(typeVar, datatype)
  }

  def updateValueVarMapping(valueVar: ValueVar, value: Value) {
    valueVarMapping.update(valueVar, value)
  }

  def resolvePolymorphicType(a: Type, b: Type): Option[(Type, Int)] = {
    a match {
      case PolymorphicType(types) =>
        types.zipWithIndex.toIterator.map {
          case (signature, index) => unify(specializeType(generalizeType(b)), signature) match {
            case Some(_) => unify(b, signature) match {
              case Some(t) => Some(t, index)
              case _ => None
            }
            case _ => None
          }
        } find ({ x: Option[(Type, Int)] => x != None }) flatten
      case _ => unify(b, a) match {
        case Some(t) => Some(t, 0)
        case _ => None
      }
    }
  }

  def resolveValueReferences(datatype: Type, arguments: List[IntermediateExpression]): Type = {
    datatype match {
      case FunctionType(args, result) => {
        FunctionType(args map { resolveValueReferences(_, arguments) }, resolveValueReferences(result, arguments))
      }
      case PolymorphicType(types) => PolymorphicType(types map { resolveValueReferences(_, arguments) })
      case MatrixType(elementType, rowValue, colValue) => {
        MatrixType(resolveValueReferences(elementType, arguments), resolveValueReferences(rowValue, arguments),
          resolveValueReferences(colValue, arguments))
      }
      case x => x
    }
  }

  def evaluateExpression(expression: IntermediateExpression): Value = {
    expression match {
      case IntermediateInteger(value) => IntValue(value)
      case IntermediateIdentifier(id, _) => {
        getValue(id) match {
          case Some(t) => evaluateExpression(t)
          case _ => throw new ValueNotFoundError("identifier " + id + " has no value assigned")
        }
      }
      case _ => throw new NotImplementedError("expression evaluation is not yet fully implemented")
    }
  }

  def resolveValueReferences(value: Value, arguments: List[IntermediateExpression]): Value = {
    value match {
      case ReferenceValue(idx) => evaluateExpression(arguments(idx))
      case x => x
    }
  }

  def widenTypes(a: Type, b: Type): (Type, Type) = {
    (a,b) match {
      case (_:NumericTypeVar, _) =>
        if(b.isWideableTo(DoubleType))
          (a,DoubleType)
        else
          (a,b)
      case (_, _:NumericTypeVar) =>
        if(a.isWideableTo(DoubleType))
          (DoubleType, b)
        else
          (a,b)
      case _ =>
        (a.isWideableTo(b), b.isWideableTo(a)) match {
          case (true, _) => (b, b)
          case (_, true) => (a, a)
          case _ => (a, b)
        }
    }

  }

  def specializeType(datatype: Type): Type = {
    val replacement = scala.collection.mutable.Map[AbstractTypeVar, AbstractTypeVar]()
    val replacementValues = scala.collection.mutable.Map[ValueVar, ValueVar]()
    var specialized: Boolean = false;
    def helper(a: Type): Type = {
      a match {
        case UniversalType(x: NumericTypeVar) =>
          specialized = true
          replacement.getOrElseUpdate(x, newNumericTV())
        case UniversalType(x: TypeVar) =>
          specialized = true
          replacement.getOrElseUpdate(x, newTV())
        case MatrixType(elementType, rowValue, colValue) => {
          MatrixType(helper(elementType), helperValue(rowValue), helperValue(colValue))
        }
        case PolymorphicType(types) => PolymorphicType(types map { specializeType })
        case FunctionType(args, result) => FunctionType(args map { helper }, helper(result))
        case ConcreteCellArrayType(types) => ConcreteCellArrayType(types map { helper })
        case x:InterimCellArrayType =>
          val specializedTypes = x.types map { helper }
          if(specialized){
            ConcreteCellArrayType(specializedTypes)
          }else{
            x
          }
        case x => x
      }
    }
    def helperValue(a: Value): Value = {
      a match {
        case UniversalValue(x: ValueVar) => replacementValues.getOrElseUpdate(x, newVV())
        case x => x
      }
    }
    helper(datatype)
  }

  def specializeValue(value: Value): Value = {
    val replacement = scala.collection.mutable.Map[ValueVar, ValueVar]()
    def helper(a: Value): Value = {
      a match {
        case UniversalValue(x: ValueVar) => replacement.getOrElseUpdate(x, newVV())
        case x => x
      }
    }
    helper(value)
  }

  def freeVariables(expression: ASTExpression): Set[String] = {
    expression match {
      case x: ASTIdentifier => freeVariables(x)
      case _: ASTInteger | _: ASTFloatingPoint | _: ASTString | _: ASTBoolean => Set()
      case ASTUnaryExpression(exp, _) => freeVariables(exp)
      case ASTBinaryExpression(a, op, b) => freeVariables(a) ++ freeVariables(b)
      case ASTFunctionReference(id) => freeVariables(id)
      case ASTAnonymousFunction(params, body) => {
        freeVariables(body) -- (params map { case ASTIdentifier(id) => id }).toSet
      }
      case ASTFunctionApplication(func, args) => freeVariables(func) ++ (args flatMap { freeVariables(_) }).toSet
      case ASTMatrix(rows) => (rows flatMap { freeVariables(_) }).toSet
      case ASTMatrixRow(exps) => (exps flatMap { freeVariables(_) }).toSet
      case ASTCellArray(cells) => (cells flatMap { freeVariables}).toSet
      case ASTCellArrayIndexing(cellArray, index) =>
        freeVariables(cellArray) ++ freeVariables(index)
    }
  }

  def freeVariables(identifier: ASTIdentifier): Set[String] = {
    identifier match {
      case ASTIdentifier(id) => {
        if (BuiltinSymbols.isSymbol(id)) {
          Set()
        } else {
          Set(id)
        }
      }
    }
  }

  def freeTypeVariables(datatype: Type): Set[Type] = {
    def helper(a: Type): Set[Type] = {
      a match {
        case UniversalType(x: AbstractTypeVar) => Set()
        case UniversalType(_) => throw new TypingError("Universal cannot be applied to a non type variable")
        case x: AbstractTypeVar => Set(x)
        case MatrixType(elementType, rowValue, colValue) => helper(elementType)
        case PolymorphicType(types) => (types flatMap (helper)).toSet
        case FunctionType(args, result) => (args flatMap (helper)).toSet ++ helper(result)
        case ConcreteCellArrayType(types) => (types flatMap(helper)).toSet
        case InterimCellArrayType(types) => (types flatMap (helper)).toSet
        case _ => Set()
      }
    }
    helper(datatype)
  }

  def generalizeType(datatype: Type): Type = {
    val freeVars = typeEnvironment.values.flatMap({ freeTypeVariables }).toSet
    def helper(datatype: Type): Type = {
      datatype match {
        case x @ UniversalType(_: AbstractTypeVar) => x
        case UniversalType(_) => throw new TypingError("Universal cannot be applied to a non type variable")
        case x: AbstractTypeVar => if (freeVars contains x) x else UniversalType(x)
        case MatrixType(elementType, rowValue, colValue) => {
          MatrixType(helper(elementType), generalizeValue(rowValue), generalizeValue(colValue))
        }
        case PolymorphicType(types) => PolymorphicType(types map { helper })
        case FunctionType(args, result) => FunctionType(args map { helper }, helper(result))
        case ConcreteCellArrayType(types) => ConcreteCellArrayType(types map { helper })
        case InterimCellArrayType(types) => ConcreteCellArrayType(types map { helper })
        case x => x
      }
    }
    helper(datatype)
  }

  def generalizeValue(value: Value): Value = {
    value match {
      case x: ValueVar => UniversalValue(x)
      case x => x
    }
  }

  def typeConversion(expression: TypedExpression, tpe: Type): TypedExpression = {
    if(structuralCompatible(expression.datatype, tpe)){
      val(typeA, typeB) = (getElementType(expression.datatype), getElementType(tpe))

      if(typeA == typeB)
        expression
      else{
        (typeA, typeB) match {
          case (_:AbstractTypeVar, _) => expression
          case (_, _:AbstractTypeVar) => expression
          case (IntegerType, DoubleType) => expression
          case _ => TypeConversion(expression, tpe)
        }
      }
    }else{
      throw new TypingError("Cannot convert type " + expression.datatype + " into type " + tpe)
    }

  }

  def unifyValue(a: Value, b: Value): Option[Value] = {
    val resolvedValueA = resolveValue(a)
    val resolvedValueB = resolveValue(b)

    if (resolvedValueA == resolvedValueB) {
      Some(resolvedValueA)
    } else {
      (resolvedValueA, resolvedValueB) match {
        case (x: ValueVar, y) => {
          updateValueVarMapping(x, y)
          Some(y)
        }
        case (x, y: ValueVar) => {
          updateValueVarMapping(y, x)
          Some(x)
        }
        case (UndefinedValue, _) => Some(UndefinedValue)
        case (_, UndefinedValue) => Some(UndefinedValue)
        case _ => None
      }
    }
  }

  //TODO: type variable contained in other type as subexpression
  def unify(a: Type, b: Type): Option[Type] = {
    val resolvedTypeA = resolveType(a)
    val resolvedTypeB = resolveType(b)

    val (typeA, typeB) = widenTypes(resolvedTypeA, resolvedTypeB)

    if (typeA == typeB) {
      Some(typeA)
    } else {
      (typeA, typeB) match {
        case (x: TypeVar, _) => {
          updateTypeVarMapping(x, typeB)
          Some(typeB)
        }
        case (_, y: TypeVar) => {
          updateTypeVarMapping(y, typeA)
          Some(typeA)
        }
        case (x: NumericTypeVar, y: NumericType) => {
          updateTypeVarMapping(x, y)
          Some(y)
        }
        case (x: NumericType, y: NumericTypeVar) => {
          updateTypeVarMapping(y, x)
          Some(x)
        }
        case (FunctionType(args1, result1), FunctionType(args2, result2)) => {
          if (args1.length != args2.length) None
          else {
            val unifiedArgs = (for ((x, y) <- (args1 zip args2)) yield {
              unify(x, y)
            }) flatMap { x => x }
            val unifiedResult = unify(result1, result2)

            unifiedResult match {
              case Some(resultType) if unifiedArgs.length == args1.length => Some(FunctionType(unifiedArgs, resultType))
              case _ => None
            }
          }
        }
        case (MatrixType(matrixType1, rows1, cols1), MatrixType(matrixType2, rows2, cols2)) => {
          val unifiedType = unify(matrixType1, matrixType2)
          val unifiedRows = unifyValue(rows1, rows2)
          val unifiedCols = unifyValue(cols1, cols2)

          unifiedType match {
            case Some(t) => unifiedRows match {
              case Some(r) => unifiedCols match {
                case Some(c) => Some(MatrixType(t, r, c))
                case _ => None
              }
              case _ => None
            }
            case _ => None
          }
        }
        case (ConcreteCellArrayType(typesA), ConcreteCellArrayType(typesB)) => {
          if(typesA.length == typesB.length){
            val unifiedTypes = typesA zip typesB flatMap { case (a,b) => unify(a,b)}
            if(unifiedTypes.length == typesA.length){
              Some(ConcreteCellArrayType(unifiedTypes))
            }else
              None
          }else
            None
        }
        case (ConcreteCellArrayType(typesA), b@InterimCellArrayType(typesB)) =>
          var tB = typesB

          if(typesB.length < typesA.length){
            tB ++= List.fill[Type](typesA.length - typesB.length)(newTV())
          }

          if(typesA.length < typesB.length){
            None
          }else{
            val unifiedTypes = typesA zip tB flatMap { case(a,b) => unify(a,b)}

            if(unifiedTypes.length == typesA.length){
              b.types = unifiedTypes
              Some(ConcreteCellArrayType(unifiedTypes))
            }else{
              None
            }
          }
        case (a@InterimCellArrayType(typesA), ConcreteCellArrayType(typesB)) =>
          var tA = typesA

          if(typesA.length < typesB.length){
            tA ++= List.fill[Type](typesB.length - typesA.length)(newTV())
          }

          if(typesB.length < typesA.length){
            None
          }else{
            val unifiedTypes = tA zip typesB flatMap { case(a,b) => unify(a,b)}
            a.types = unifiedTypes
            Some(ConcreteCellArrayType(unifiedTypes))
          }
        case (a@InterimCellArrayType(typesA),b@ InterimCellArrayType(typesB)) =>
          var tA = typesA
          var tB = typesB
          if(typesA.length < typesB.length){
           tA ++= List.fill[Type](typesB.length-typesA.length)(newTV())
          }

          if(typesA.length > typesB.length){
            tB ++= List.fill[Type](typesA.length - typesB.length)(newTV())
          }

          val unifiedTypes = tA zip tB flatMap { case(a,b) => unify(a,b)}
          a.types = unifiedTypes
          b.types = unifiedTypes

          if(unifiedTypes.length == tA.length){
            Some(InterimCellArrayType(unifiedTypes))
          }else{
            None
          }
        case (PolymorphicType(typesA), PolymorphicType(typesB)) => None
        case (CharacterType, _) | (_, CharacterType) => None
        case (_, _) => None
      }
    }
  }

  def extractIdentifiers(program: ASTProgram): Set[ASTIdentifier] = Set()

  def extractIdentifiers(expression: ASTExpression): Set[String] = {
    def helper(exp: ASTExpression): Set[String] = {
      exp match {
        case ASTIdentifier(id) => Set(id)
        case _: ASTInteger | _: ASTFloatingPoint | _: ASTString | _: ASTBoolean => Set()
        case ASTUnaryExpression(exp, _) => helper(exp)
        case ASTBinaryExpression(a, _, b) => helper(a) ++ helper(b)
        case ASTAnonymousFunction(parameters, body) => {
          helper(body) -- (parameters map { case ASTIdentifier(id) => id }).toSet
        }
        case ASTFunctionApplication(function, parameters) => {
          helper(function) ++ (parameters flatMap { helper }).toSet
        }
        case ASTFunctionReference(function) => helper(function)
        case ASTMatrix(rows) => rows flatMap { helper } toSet
        case ASTMatrixRow(exps) => exps flatMap { helper } toSet
        case ASTCellArray(elements) => elements flatMap {helper} toSet
        case ASTCellArrayIndexing(cellArray, index) => helper(cellArray) ++ helper(index)
      }
    }
    helper(expression)
  }

  def removeFromEnvironment(identifier: String) = {
    typeEnvironment.remove(identifier)
  }
  def updateEnvironment(identifier: String, datatype: Type) = typeEnvironment.update(identifier, datatype)
  def updateEnvironment(identifier: ASTIdentifier, datatype: Type) = typeEnvironment.update(identifier.value, datatype)

  def updateValueEnvironment(identifier: ASTIdentifier, expression: IntermediateExpression): Unit = {
    updateValueEnvironment(identifier.value, expression)
  }

  def updateValueEnvironment(identifier: String, expression: IntermediateExpression): Unit = {
    valueEnvironment.update(identifier, expression)
  }

  def getType(id: String): Option[Type] = {
    BuiltinSymbols.getType(id) match {
      case None => typeEnvironment.get(id) match {
        case Some(t) => Some(resolveType(specializeType(t)))
        case None => None
      }
      case Some(t) => Some(resolveType(specializeType(t)))
    }
  }

  def getValue(id: String): Option[IntermediateExpression] = {
    valueEnvironment.get(id)
  }

  def extractType(expression: IntermediateExpression) = expression.datatype

  def typeProgram(program: ASTProgram): TypedProgram = {
    val intermediateProgram = intermediateRepresentationProgram(program)
    finalizeTyping(intermediateProgram)
  }

  def typeStatement(stmt: ASTStatement): TypedStatement = {
    val intermediateStmt = intermediateRepresentationStatement(stmt)
    finalizeTyping(intermediateStmt)
  }

  def typeExpression(expression: ASTExpression): TypedExpression = {
    val intermediateExpression = intermediateRepresentationExpression(expression)

    finalizeTyping(intermediateExpression)
  }

  def typeIdentifier(identifier: ASTIdentifier): TypedIdentifier = {
    val intermediateIdentifier = intermediateRepresentationIdentifier(identifier)
    finalizeTyping(intermediateIdentifier)
  }

  def intermediateRepresentationProgram(program: ASTProgram): IntermediateProgram = {
    program match {
      case ASTProgram(stmtFuncList) => IntermediateProgram(stmtFuncList map {
        case stmt: ASTStatement => intermediateRepresentationStatement(stmt)
        case func: ASTFunction => intermediateRepresentationFunction(func)
        case typeAnnotation: ASTTypeAnnotation =>
          throw new NotYetImplementedError("Type annotations are not yet supported")
      })
    }

  }


  def intermediateRepresentationStatement(stmt: ASTStatement): IntermediateStatement = stmt match {
    case ASTOutputResultStatement(stmt) => IntermediateOutputResultStatement(intermediateRepresentationStmtWithResult
      (stmt))
    case ASTNOP => IntermediateNOP
    case x: ASTStatementWithResult => intermediateRepresentationStmtWithResult(x)
  }

  def intermediateRepresentationStmtWithResult(stmt: ASTStatementWithResult): IntermediateStatementWithResult = stmt
  match {
    case ASTAssignment(lhs, rhs) => {
      val typedRHS = intermediateRepresentationExpression(rhs)
      updateValueEnvironment(lhs, typedRHS)
      typedRHS match {
        case x: IntermediateFunctionExpression =>
          val generalizedRHS = generalizeType(extractType(typedRHS))
          updateEnvironment(lhs, generalizedRHS)
        case _ => updateEnvironment(lhs, extractType(typedRHS))
      }
      IntermediateAssignment(intermediateRepresentationIdentifier(lhs), typedRHS)
    }
    case exp: ASTExpression => intermediateRepresentationExpression(exp)
  }

  def intermediateRepresentationExpression(exp: ASTExpression): IntermediateExpression = exp match {
    case id: ASTIdentifier => intermediateRepresentationIdentifier(id)
    case ASTInteger(value) => IntermediateInteger(value)
    case ASTFloatingPoint(value) => IntermediateFloatingPoint(value)
    case ASTString(value) => IntermediateString(value)
    case ASTBoolean(value) => IntermediateBoolean(value)
    case ASTUnaryExpression(exp, op) => {
      val typedExpression = intermediateRepresentationExpression(exp)
      val operatorType = typeOperator(op)
      val unificationResult = resolvePolymorphicType(operatorType, FunctionType(extractType(typedExpression), newTV()))

      unificationResult match {
        case Some((FunctionType(List(tpe), resultType), _)) =>
          IntermediateUnaryExpression(typedExpression,tpe, op, resultType)
        case _ => throw new TypeNotFoundError("Unary expression: " + ASTUnaryExpression(exp, op))
      }
    }
    case ASTBinaryExpression(a, op, b) => {
      val typedExpressionA = intermediateRepresentationExpression(a)
      val typedExpressionB = intermediateRepresentationExpression(b)
      val operatorType = typeOperator(op)
      val unificationResult = resolvePolymorphicType(operatorType, FunctionType(List(extractType(typedExpressionA),
        extractType(typedExpressionB)), newTV()))

      unificationResult match {
        case Some((FunctionType(List(typeA, typeB), resultType), _)) => {

          IntermediateBinaryExpression(typedExpressionA,typeA, op, typedExpressionB, typeB,
            resultType)
        }
        case _ => throw new TypeNotFoundError("Binary expression: " + ASTBinaryExpression(a, op, b))
      }
    }
    case ASTFunctionApplication(func, arguments) => {
      val typedFunc = intermediateRepresentationIdentifier(func)
      val functionType = extractType(typedFunc)
      val typedArguments = arguments map { intermediateRepresentationExpression(_) }

      val unificationResult = resolvePolymorphicType(functionType, FunctionType(typedArguments map
        { extractType(_) }, newTV()))

      unificationResult match {
        case Some((appliedFunType @ FunctionType(parameterTypes, resultType), _)) =>
          IntermediateFunctionApplication(IntermediateIdentifier(typedFunc.value,appliedFunType), typedArguments, parameterTypes,
            resolveValueReferences(resultType, typedArguments))
        case _ => throw new TypeNotFoundError("Function application could not be typed: " + exp)
      }
    }
    case ASTAnonymousFunction(parameters, body) => {
      val oldMappings = parameters map { case ASTIdentifier(id) => (id, typeEnvironment.get(id)) }
      parameters foreach { case ASTIdentifier(id) => updateEnvironment(id, newTV()) }

      val closure = (freeVariables(body) -- (parameters map { case ASTIdentifier(id) => id }).toSet).toList
      val typedBody = intermediateRepresentationExpression(body)

      val typedParameters = parameters map {
        case ASTIdentifier(id) => IntermediateIdentifier(id, getType(id) match {
          case Some(t) => t
          case _ => throw new TypeNotFoundError("Type for parameter " + id + " could not be found")
        })
      }

      oldMappings foreach {
        case (id, Some(datatype)) => updateEnvironment(id, datatype)
        case (id, None) => removeFromEnvironment(id);
      }

      val functionType = FunctionType(typedParameters map { extractType(_) }, extractType(typedBody))

      IntermediateAnonymousFunction(typedParameters, typedBody, closure, functionType)
    }
    case ASTFunctionReference(func) => {
      val typedIdentifier = intermediateRepresentationIdentifier(func)

      typedIdentifier.datatype match {
        case x: FunctionType => IntermediateFunctionReference(typedIdentifier, typedIdentifier.datatype)
        case _ => throw new TypingError("Identifier " + func.value + " has to be a function type")
      }
    }

    case ASTCellArray(elements) => {
      val typedElements = elements map {intermediateRepresentationExpression}
      IntermediateCellArray(typedElements, ConcreteCellArrayType(typedElements map { extractType }))
    }

    case ASTCellArrayIndexing(cellArray, index) => {
      val typedExp = intermediateRepresentationExpression(cellArray)

      extractType(typedExp) match {
        case ConcreteCellArrayType(types) =>
          val idx = index.value

          if(idx <0 || idx >= types.length )
            throw new TypingError(s"Cell array index is out of bounds.")

          IntermediateCellArrayIndexing(typedExp, idx, types(idx))
        case typeVar: TypeVar =>
          val idx = index.value
          val elementTypes = List.fill[Type](idx+1)(newTV())
          updateTypeVarMapping(typeVar,InterimCellArrayType(elementTypes))
          IntermediateCellArrayIndexing(typedExp, idx, elementTypes(idx))
        case x:InterimCellArrayType =>
          val idx = index.value
          val types = x.types

          if(idx < 0)
            throw new TypingError(s"Cell array index cannot be negative.")
          else{
            if(idx >= types.length){
              val newTypes = types ++ List.fill[Type](idx - types.length+1)(newTV())
              x.types = newTypes
              IntermediateCellArrayIndexing(typedExp, idx, newTypes(idx))
            }else{
              IntermediateCellArrayIndexing(typedExp, idx, types(idx))
            }
          }
        case _ => throw new TypingError(s"Object $typedExp has to be a cell array in order to be indexed.")
      }
    }

    case _ => throw new NotImplementedError("")
  }

  def typeOperator(operator: Operator): Type = {
    BuiltinOperators.getType(operator) match{
      case Some(t) => specializeType(t)
      case _ => throw new TypeNotFoundError("Operator " + operator + " has no type.")
    }
  }

  def intermediateRepresentationIdentifier(id: ASTIdentifier) = id match {
    case ASTIdentifier(id) => {
      val idType = getType(id) match {
        case Some(t) => t
        case _ => throw new TypeNotFoundError("Identifier " + id + " is unbound")
      }
      IntermediateIdentifier(id, idType)
    }
  }

  def intermediateRepresentationFunction(func: ASTFunction): IntermediateFunction = {
    val typer = new Typer(typeEnvironment.clone, valueEnvironment.clone(), typeVarMapping.clone(),
    valueVarMapping.clone())

    func.values foreach { typer.updateEnvironment(_, newTV()) }
    func.parameters foreach { typer.updateEnvironment(_, newTV()) }

    val intermediateBody = typer.intermediateRepresentationProgram(func.body)
    val intermediateValues = func.values map { typer.intermediateRepresentationIdentifier }
    val intermediateParameters = func.parameters map { typer.intermediateRepresentationIdentifier }
    val resultType = if (intermediateValues.length == 0) VoidType else extractType(intermediateValues(0))
    val intermediateFunctionName = IntermediateIdentifier(func.identifier.value,
      generalizeType(FunctionType(intermediateParameters map { extractType }, resultType)))

    updateEnvironment(func.identifier, intermediateFunctionName.datatype)

    IntermediateFunction(intermediateValues, intermediateFunctionName, intermediateParameters, intermediateBody)
  }

}
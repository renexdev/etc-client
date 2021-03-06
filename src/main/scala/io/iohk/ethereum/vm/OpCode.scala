package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import GasFee._

// scalastyle:off magic.number
// scalastyle:off number.of.types
object OpCode {

  val opcodes: List[OpCode] = List(
    STOP,
    ADD,
    MUL,
    SUB,
    DIV,
    SDIV,
    MOD,
    SMOD,
    ADDMOD,
    MULMOD,
    EXP,
    SIGNEXTEND,

    LT,
    GT,
    SLT,
    SGT,
    EQ,
    ISZERO,
    AND,
    OR,
    XOR,
    NOT,
    BYTE,

    SHA3,

    //ADDRESS,
    //BALANCE,
    //ORIGIN,
    //CALLER,
    CALLVALUE,
    CALLDATALOAD,
    //CALLDATASIZE,
    //CALLDATACOPY,
    //CODESIZE,
    CODECOPY,
    //GASPRICE,
    //EXTCODESIZE,
    EXTCODECOPY,

    //BLOCKHASH,
    //COINBASE,
    //TIMESTAMP,
    //NUMBER,
    //DIFFICULTY,
    //GASLIMIT,

    POP,
    MLOAD,
    MSTORE,
    //MSTORE8,
    SLOAD,
    SSTORE,
    JUMP,
    JUMPI,
    //PC,
    //MSIZE,
    //GAS,
    JUMPDEST,

    PUSH1,
    PUSH2,
    PUSH3,
    PUSH4,
    PUSH5,
    PUSH6,
    PUSH7,
    PUSH8,
    PUSH9,
    PUSH10,
    PUSH11,
    PUSH12,
    PUSH13,
    PUSH14,
    PUSH15,
    PUSH16,
    PUSH17,
    PUSH18,
    PUSH19,
    PUSH20,
    PUSH21,
    PUSH22,
    PUSH23,
    PUSH24,
    PUSH25,
    PUSH26,
    PUSH27,
    PUSH28,
    PUSH29,
    PUSH30,
    PUSH31,
    PUSH32,

    DUP1,
    DUP2,
    DUP3,
    DUP4,
    DUP5,
    DUP6,
    DUP7,
    DUP8,
    DUP9,
    DUP10,
    DUP11,
    DUP12,
    DUP13,
    DUP14,
    DUP15,
    DUP16,

    SWAP1,
    SWAP2,
    SWAP3,
    SWAP4,
    SWAP5,
    SWAP6,
    SWAP7,
    SWAP8,
    SWAP9,
    SWAP10,
    SWAP11,
    SWAP12,
    SWAP13,
    SWAP14,
    SWAP15,
    SWAP16,

    LOG0,
    LOG1,
    LOG2,
    LOG3,
    LOG4,

    //CREATE,
    //CALL,
    //CALLCODE,
    RETURN
    //DELEGATECALL,
    //SUICIDE
  )

  val byteToOpCode: Map[Byte, OpCode] =
    opcodes.map(op => op.code -> op).toMap
}

/**
  * Base class for all the opcodes of the EVM
  *
  * @param code Opcode byte representation
  * @param delta number of words to be popped from stack
  * @param alpha number of words to be pushed to stack
  */
sealed abstract class OpCode(val code: Byte, val delta: Int, val alpha: Int, val constGas: BigInt) {
  def this(code: Int, pop: Int, push: Int, constGas: BigInt) = this(code.toByte, pop, push, constGas)

  def execute(state: ProgramState): ProgramState = {
    if (state.stack.size < delta)
      state.withError(StackUnderflow)
    else if (state.stack.size - delta + alpha > state.stack.maxSize)
      state.withError(StackOverflow)
    else {
      val gas = constGas + varGas(state)
      if (gas > state.gas)
        state.withError(OutOfGas)
      else
        exec(state).spendGas(gas)
    }
  }

  protected def varGas(state: ProgramState): BigInt

  protected def exec(state: ProgramState): ProgramState
}

sealed trait ConstGas { self: OpCode =>
  protected def varGas(state: ProgramState): BigInt = 0
}

case object STOP extends OpCode(0x00, 0, 0, G_zero) with ConstGas {
  protected def exec(state: ProgramState): ProgramState =
    state.halt
}

sealed abstract class UnaryOp(code: Int, constGas: BigInt)(val f: DataWord => DataWord) extends OpCode(code, 1, 1, constGas) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    val (a, stack1) = state.stack.pop
    val res = f(a)
    val stack2 = stack1.push(res)
    state.withStack(stack2).step()
  }
}

sealed abstract class BinaryOp(code: Int, constGas: BigInt)(val f: (DataWord, DataWord) => DataWord)
  extends OpCode(code.toByte, 2, 1, constGas) {

  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(a, b), stack1) = state.stack.pop(2)
    val res = f(a, b)
    val stack2 = stack1.push(res)
    state.withStack(stack2).step()
  }
}

sealed abstract class TernaryOp(code: Int, constGas: BigInt)(val f: (DataWord, DataWord, DataWord) => DataWord)
    extends OpCode(code.toByte, 3, 1, constGas) {

  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(a, b, c), stack1) = state.stack.pop(3)
    val res = f(a, b, c)
    val stack2 = stack1.push(res)
    state.withStack(stack2).step()
  }
}

case object ADD extends BinaryOp(0x01, G_verylow)(_ + _) with ConstGas

case object MUL extends BinaryOp(0x02, G_low)(_ * _) with ConstGas

case object SUB extends BinaryOp(0x03, G_verylow)(_ - _) with ConstGas

case object DIV extends BinaryOp(0x04, G_low)(_ div _) with ConstGas

case object SDIV extends BinaryOp(0x05, G_low)(_ sdiv _) with ConstGas

case object MOD extends BinaryOp(0x06, G_low)(_ mod _) with ConstGas

case object SMOD extends BinaryOp(0x06, G_low)(_ smod _) with ConstGas

case object ADDMOD extends TernaryOp(0x07, G_mid)(_.addmod(_, _)) with ConstGas

case object MULMOD extends TernaryOp(0x08, G_mid)(_.mulmod(_, _)) with ConstGas

case object EXP extends BinaryOp(0x0a, G_exp)(_ ** _) {
  protected def varGas(state: ProgramState): BigInt = {
    val (Seq(_, m: DataWord), _) = state.stack.pop(2)
    G_expbyte * m.byteSize
  }
}

case object SIGNEXTEND extends BinaryOp(0x0b, G_low)((a, b) => b signExtend a) with ConstGas

case object LT extends BinaryOp(0x10, G_verylow)((a, b) => DataWord(a < b)) with ConstGas

case object GT extends BinaryOp(0x11, G_verylow)((a, b) => DataWord(a > b)) with ConstGas

case object SLT extends BinaryOp(0x12, G_verylow)(_ slt _) with ConstGas

case object SGT extends BinaryOp(0x13, G_verylow)(_ sgt _) with ConstGas

case object EQ extends BinaryOp(0x14, G_verylow)((a, b) => DataWord(a == b)) with ConstGas

case object ISZERO extends UnaryOp(0x15, G_verylow)(a => DataWord(a.isZero)) with ConstGas

case object AND extends BinaryOp(0x16, G_verylow)(_ & _) with ConstGas

case object OR extends BinaryOp(0x17, G_verylow)(_ | _) with ConstGas

case object XOR extends BinaryOp(0x18, G_verylow)(_ ^ _) with ConstGas

case object NOT extends UnaryOp(0x19, G_verylow)(~_) with ConstGas

case object BYTE extends BinaryOp(0x1a, G_verylow)((a, b) => b getByte a) with ConstGas

case object SHA3 extends OpCode(0x20, 2, 1, G_sha3) {
  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(addr, size), stack1) = state.stack.pop(2)
    val (input, mem1) = state.memory.load(addr, size)
    val hash = kec256(input.toArray)
    val ret = DataWord(hash)
    val stack2 = stack1.push(ret)
    state.withStack(stack2).withMemory(mem1).step()
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (Seq(addr, size), _) = state.stack.pop(2)
    val memCost = calcMemCost(state.memory.size, addr, size)
    val shaCost = G_sha3word * wordsForBytes(size)
    memCost + shaCost
  }
}

case object CALLVALUE extends OpCode(0x34, 0, 1, G_base) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    val stack1 = state.stack.push(DataWord(state.env.value))
    state.withStack(stack1).step()
  }
}

case object CALLDATALOAD extends OpCode(0x35, 1, 1, G_verylow) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    val (offset, stack1) = state.stack.pop
    val i = offset.intValue
    val data = state.inputData.slice(i, i + 32).padTo(32, 0.toByte)
    val stack2 = stack1.push(DataWord(data))
    state.withStack(stack2).step()
  }
}

case object CODECOPY extends OpCode(0x39, 3, 0, G_verylow) {
  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(memOffset, codeOffset, size), stack1) = state.stack.pop(3)
    val bytes = state.program.getBytes(codeOffset.intValue, size.intValue)
    val mem1 = state.memory.store(memOffset, bytes)
    state.withStack(stack1).withMemory(mem1).step()
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (Seq(addr, _, size), _) = state.stack.pop(3)
    val memCost = calcMemCost(state.memory.size, addr, size)
    val copyCost = G_copy * wordsForBytes(size)
    memCost + copyCost
  }
}

case object EXTCODECOPY extends OpCode(0x3c, 4, 0, G_extcode) {
  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(address, memOffset, codeOffset, size), stack1) = state.stack.pop(4)
    val codeCopy = ByteString() //TODO: copy external code
    val mem1 = state.memory.store(memOffset, codeCopy)
    state.withStack(stack1).withMemory(mem1).step()
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (Seq(_, addr, _, size), _) = state.stack.pop(4)
    val memCost = calcMemCost(state.memory.size, addr, size)
    val copyCost = G_copy * wordsForBytes(size)
    memCost + copyCost
  }
}

case object POP extends OpCode(0x50, 1, 0, G_base) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    val (_, stack1) = state.stack.pop
    state.withStack(stack1).step()
  }
}

case object MLOAD extends OpCode(0x51, 1, 1, G_verylow) {
  protected def exec(state: ProgramState): ProgramState = {
    val (addr, stack1) = state.stack.pop
    val (word, mem1) = state.memory.load(addr)
    val stack2 = stack1.push(word)
    state.withStack(stack2).withMemory(mem1).step()
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (addr, _) = state.stack.pop
    calcMemCost(state.memory.size, addr, DataWord.Size)
  }
}

case object MSTORE extends OpCode(0x52, 2, 0, G_verylow) {
  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(addr, value), stack1) = state.stack.pop(2)
    val updatedMem = state.memory.store(addr, value)
    state.withStack(stack1).withMemory(updatedMem).step()
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (addr, _) = state.stack.pop
    calcMemCost(state.memory.size, addr, DataWord.Size)
  }
}

case object SLOAD extends OpCode(0x54, 1, 1, G_sload) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    val (addr, stack1) = state.stack.pop
    val value = state.storage.load(addr)
    val stack2 = stack1.push(value)
    state.withStack(stack2).step()
  }
}

case object SSTORE extends OpCode(0x55, 2, 0, G_zero) {
  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(addr, value), stack1) = state.stack.pop(2)
    val oldValue = state.storage.load(addr)
    val refund = if (value.isZero && !oldValue.isZero) R_sclear else BigInt(0)
    val updatedStorage = state.storage.store(addr, value)
    state.withStack(stack1).withStorage(updatedStorage).refundGas(refund).step()
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (Seq(addr, value), _) = state.stack.pop(2)
    val oldValue = state.storage.load(addr)
    if (oldValue.isZero && !value.isZero) G_sset else G_sreset
  }
}

case object JUMP extends OpCode(0x56, 1, 0, G_mid) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    val (pos, stack1) = state.stack.pop
    //TODO: JUMPDEST validation
    state.withStack(stack1).goto(pos.intValue)
  }
}

case object JUMPI extends OpCode(0x57, 2, 0, G_high) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(pos, cond), stack1) = state.stack.pop(2)
    val nextPos = if (!cond.isZero) pos.intValue else state.pc + 1
    //TODO: JUMPDEST validation
    state.withStack(stack1).goto(nextPos)
  }
}

case object JUMPDEST extends OpCode(0x5b, 0, 0, G_jumpdest) with ConstGas {
  protected def exec(state: ProgramState): ProgramState = {
    state.step()
  }
}

sealed abstract class PushOp(code: Int) extends OpCode(code, 0, 1, G_verylow) with ConstGas {
  val i: Int = code - 0x60

  protected def exec(state: ProgramState): ProgramState = {
    val n = i + 1
    val bytes = state.program.getBytes(state.pc + 1, n)
    val word = DataWord(bytes)
    val stack1 = state.stack.push(word)
    state.withStack(stack1).step(n + 1)
  }
}

case object PUSH1  extends PushOp(0x60)
case object PUSH2  extends PushOp(0x61)
case object PUSH3  extends PushOp(0x62)
case object PUSH4  extends PushOp(0x63)
case object PUSH5  extends PushOp(0x64)
case object PUSH6  extends PushOp(0x65)
case object PUSH7  extends PushOp(0x66)
case object PUSH8  extends PushOp(0x67)
case object PUSH9  extends PushOp(0x68)
case object PUSH10 extends PushOp(0x69)
case object PUSH11 extends PushOp(0x6a)
case object PUSH12 extends PushOp(0x6b)
case object PUSH13 extends PushOp(0x6c)
case object PUSH14 extends PushOp(0x6d)
case object PUSH15 extends PushOp(0x6e)
case object PUSH16 extends PushOp(0x6f)
case object PUSH17 extends PushOp(0x70)
case object PUSH18 extends PushOp(0x71)
case object PUSH19 extends PushOp(0x72)
case object PUSH20 extends PushOp(0x73)
case object PUSH21 extends PushOp(0x74)
case object PUSH22 extends PushOp(0x75)
case object PUSH23 extends PushOp(0x76)
case object PUSH24 extends PushOp(0x77)
case object PUSH25 extends PushOp(0x78)
case object PUSH26 extends PushOp(0x79)
case object PUSH27 extends PushOp(0x7a)
case object PUSH28 extends PushOp(0x7b)
case object PUSH29 extends PushOp(0x7c)
case object PUSH30 extends PushOp(0x7d)
case object PUSH31 extends PushOp(0x7e)
case object PUSH32 extends PushOp(0x7f)


sealed abstract class DupOp private(code: Int, val i: Int) extends OpCode(code, i + 1, i + 2, G_verylow) with ConstGas {
  def this(code: Int) = this(code, code - 0x80)

  protected def exec(state: ProgramState): ProgramState = {
    val stack1 = state.stack.dup(i)
    state.withStack(stack1).step()
  }
}

case object DUP1  extends DupOp(0x80)
case object DUP2  extends DupOp(0x81)
case object DUP3  extends DupOp(0x82)
case object DUP4  extends DupOp(0x83)
case object DUP5  extends DupOp(0x84)
case object DUP6  extends DupOp(0x85)
case object DUP7  extends DupOp(0x86)
case object DUP8  extends DupOp(0x87)
case object DUP9  extends DupOp(0x88)
case object DUP10 extends DupOp(0x89)
case object DUP11 extends DupOp(0x8a)
case object DUP12 extends DupOp(0x8b)
case object DUP13 extends DupOp(0x8c)
case object DUP14 extends DupOp(0x8d)
case object DUP15 extends DupOp(0x8e)
case object DUP16 extends DupOp(0x8f)


sealed abstract class SwapOp(code: Int, val i: Int) extends OpCode(code, i + 2, i + 2, G_verylow) with ConstGas {
  def this(code: Int) = this(code, code - 0x90)

  protected def exec(state: ProgramState): ProgramState = {
    val stack1 = state.stack.swap(i + 1)
    state.withStack(stack1).step()
  }
}

case object SWAP1  extends SwapOp(0x90)
case object SWAP2  extends SwapOp(0x91)
case object SWAP3  extends SwapOp(0x92)
case object SWAP4  extends SwapOp(0x93)
case object SWAP5  extends SwapOp(0x94)
case object SWAP6  extends SwapOp(0x95)
case object SWAP7  extends SwapOp(0x96)
case object SWAP8  extends SwapOp(0x97)
case object SWAP9  extends SwapOp(0x98)
case object SWAP10 extends SwapOp(0x99)
case object SWAP11 extends SwapOp(0x9a)
case object SWAP12 extends SwapOp(0x9b)
case object SWAP13 extends SwapOp(0x9c)
case object SWAP14 extends SwapOp(0x9d)
case object SWAP15 extends SwapOp(0x9e)
case object SWAP16 extends SwapOp(0x9f)


sealed abstract class LogOp(code: Int, val i: Int) extends OpCode(code, i + 2, 0, G_log) {
  def this(code: Int) = this(code, code - 0xa0)

  protected def exec(state: ProgramState): ProgramState = {
    val (_, stack1) = state.stack.pop(delta)
    //TODO: actual logging
    state.withStack(stack1).step()
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (Seq(addr, size, _*), stack1) = state.stack.pop(delta)
    val memCost = calcMemCost(state.memory.size, addr, size)
    val logCost = G_logdata * size + i * G_logtopic
    memCost + logCost
  }
}

case object LOG0 extends LogOp(0xa0)
case object LOG1 extends LogOp(0xa1)
case object LOG2 extends LogOp(0xa2)
case object LOG3 extends LogOp(0xa3)
case object LOG4 extends LogOp(0xa4)


case object RETURN extends OpCode(0xf3, 2, 0, G_zero) {
  protected def exec(state: ProgramState): ProgramState = {
    val (Seq(addr, size), stack1) = state.stack.pop(2)
    val (ret, mem1) = state.memory.load(addr, size)
    state.withStack(stack1).withReturnData(ret).withMemory(mem1).halt
  }

  protected def varGas(state: ProgramState): BigInt = {
    val (Seq(addr, size), _) = state.stack.pop(2)
    calcMemCost(state.memory.size, addr, size)
  }
}

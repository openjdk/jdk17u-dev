/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "asm/assembler.hpp"
#include "asm/assembler.inline.hpp"
#include "runtime/stubRoutines.hpp"
#include "macroAssembler_x86.hpp"

#ifdef PRODUCT
#define BLOCK_COMMENT(str) /* nothing */
#else
#define BLOCK_COMMENT(str) block_comment(str)
#endif // PRODUCT

#define BIND(label) bind(label); BLOCK_COMMENT(#label ":")

#ifdef _LP64

// Constants
ATTRIBUTE_ALIGNED(64) static const uint64_t _round_consts_arr[24] = {
      0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL,
      0x8000000080008000L, 0x000000000000808BL, 0x0000000080000001L,
      0x8000000080008081L, 0x8000000000008009L, 0x000000000000008AL,
      0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
      0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L,
      0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L,
      0x000000000000800AL, 0x800000008000000AL, 0x8000000080008081L,
      0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

ATTRIBUTE_ALIGNED(64) static const uint64_t _permsAndRots[] = {
    // permutation in combined rho and pi
    9, 2, 11, 0, 1, 2, 3, 4,   // step 1 and 3
    8, 1, 9, 2, 11, 4, 12, 0,  // step 2
    9, 2, 10, 3, 11, 4, 12, 0, // step 4
    8, 9, 2, 3, 4, 5, 6, 7,    // step 5
    0, 8, 9, 10, 15, 0, 0, 0,  // step 6
    4, 5, 8, 9, 6, 7, 10, 11,  // step 7 and 8
    0, 1, 2, 3, 13, 0, 0, 0,   // step 9
    2, 3, 0, 1, 11, 0, 0, 0,   // step 10
    4, 5, 6, 7, 14, 0, 0, 0,   // step 11
    14, 15, 12, 13, 4, 0, 0, 0, // step 12
    // size of rotations (after step 5)
    1, 6, 62, 55, 28, 20, 27, 36,
    3, 45, 10, 15, 25, 8, 39, 41,
    44, 43, 21, 18, 2, 61, 56, 14,
    // rotation of row elements
    12, 8, 9, 10, 11, 5, 6, 7,
    9, 10, 11, 12, 8, 5, 6, 7
};

void MacroAssembler::sha3_AVX3(Register permsAndRots, Register round_consts, Register constant2use, Register roundsLeft,
                               Register buf, Register state, Register block_size, Register ofs, Register limit, bool multiBlock)
{
  Label sha3_loop;
  Label rounds24_loop, block104, block136, block144, block168;

  address round_consts_arr_addr = (address)_round_consts_arr;
  address permsAndRots_addr = (address)_permsAndRots;

#ifdef _WIN64
  // on win64, fill limit from stack position
  const Address limit_mem(rbp, 6 * wordSize);
  movptr(limit, limit_mem);
#endif

  lea(permsAndRots, ExternalAddress(permsAndRots_addr));
  lea(round_consts, ExternalAddress(round_consts_arr_addr));

  // set up the masks
  movl(rax, 0x1F);
  kmovwl(k5, rax);
  kshiftrwl(k4, k5, 1);
  kshiftrwl(k3, k5, 2);
  kshiftrwl(k2, k5, 3);
  kshiftrwl(k1, k5, 4);

  // load the state
  evmovdquq(xmm0, k5, Address(state, 0), false, Assembler::AVX_512bit);
  evmovdquq(xmm1, k5, Address(state, 40), false, Assembler::AVX_512bit);
  evmovdquq(xmm2, k5, Address(state, 80), false, Assembler::AVX_512bit);
  evmovdquq(xmm3, k5, Address(state, 120), false, Assembler::AVX_512bit);
  evmovdquq(xmm4, k5, Address(state, 160), false, Assembler::AVX_512bit);

  // load the permutation and rotation constants
  evmovdquq(xmm17, Address(permsAndRots, 0), Assembler::AVX_512bit);
  evmovdquq(xmm18, Address(permsAndRots, 64), Assembler::AVX_512bit);
  evmovdquq(xmm19, Address(permsAndRots, 128), Assembler::AVX_512bit);
  evmovdquq(xmm20, Address(permsAndRots, 192), Assembler::AVX_512bit);
  evmovdquq(xmm21, Address(permsAndRots, 256), Assembler::AVX_512bit);
  evmovdquq(xmm22, Address(permsAndRots, 320), Assembler::AVX_512bit);
  evmovdquq(xmm23, Address(permsAndRots, 384), Assembler::AVX_512bit);
  evmovdquq(xmm24, Address(permsAndRots, 448), Assembler::AVX_512bit);
  evmovdquq(xmm25, Address(permsAndRots, 512), Assembler::AVX_512bit);
  evmovdquq(xmm26, Address(permsAndRots, 576), Assembler::AVX_512bit);
  evmovdquq(xmm27, Address(permsAndRots, 640), Assembler::AVX_512bit);
  evmovdquq(xmm28, Address(permsAndRots, 704), Assembler::AVX_512bit);
  evmovdquq(xmm29, Address(permsAndRots, 768), Assembler::AVX_512bit);
  evmovdquq(xmm30, Address(permsAndRots, 832), Assembler::AVX_512bit);
  evmovdquq(xmm31, Address(permsAndRots, 896), Assembler::AVX_512bit);

  BIND(sha3_loop);

  // there will be 24 keccak rounds
  movl(roundsLeft, 24);
  // load round_constants base
  movptr(constant2use, round_consts);

  // load input: 72, 104, 136, 144 or 168 bytes
  // i.e. 5+4, 2*5+3, 3*5+2, 3*5+3 or 4*5+1 longs
  evpxorq(xmm0, k5, xmm0, Address(buf, 0), true, Assembler::AVX_512bit);

  // if(blockSize == 72) SHA3-512
  cmpl(block_size, 72);
  jcc(Assembler::notEqual, block104);
  evpxorq(xmm1, k4, xmm1, Address(buf, 40), true, Assembler::AVX_512bit);
  jmp(rounds24_loop);

  // if(blockSize == 104) SHA3-384
  BIND(block104);
  cmpl(block_size, 104);
  jcc(Assembler::notEqual, block136);
  evpxorq(xmm1, k5, xmm1, Address(buf, 40), true, Assembler::AVX_512bit);
  evpxorq(xmm2, k3, xmm2, Address(buf, 80), true, Assembler::AVX_512bit);
  jmp(rounds24_loop);

  // if(blockSize == 136) SHA3-256 and SHAKE256
  BIND(block136);
  cmpl(block_size, 136);
  jcc(Assembler::notEqual, block144);
  evpxorq(xmm1, k5, xmm1, Address(buf, 40), true, Assembler::AVX_512bit);
  evpxorq(xmm2, k5, xmm2, Address(buf, 80), true, Assembler::AVX_512bit);
  evpxorq(xmm3, k2, xmm3, Address(buf, 120), true, Assembler::AVX_512bit);
  jmp(rounds24_loop);

  // if(blockSize == 144) SHA3-224
  BIND(block144);
  cmpl(block_size, 144);
  jcc(Assembler::notEqual, block168);
  evpxorq(xmm1, k5, xmm1, Address(buf, 40), true, Assembler::AVX_512bit);
  evpxorq(xmm2, k5, xmm2, Address(buf, 80), true, Assembler::AVX_512bit);
  evpxorq(xmm3, k3, xmm3, Address(buf, 120), true, Assembler::AVX_512bit);
  jmp(rounds24_loop);

  // if(blockSize == 168) SHAKE128
  BIND(block168);
  evpxorq(xmm1, k5, xmm1, Address(buf, 40), true, Assembler::AVX_512bit);
  evpxorq(xmm2, k5, xmm2, Address(buf, 80), true, Assembler::AVX_512bit);
  evpxorq(xmm3, k5, xmm3, Address(buf, 120), true, Assembler::AVX_512bit);
  evpxorq(xmm4, k1, xmm4, Address(buf, 160), true, Assembler::AVX_512bit);

  // The 24 rounds of the keccak transformation.
  // The implementation closely follows the Java version, with the state
  // array "rows" in the lowest 5 64-bit slots of zmm0 - zmm4, i.e.
  // each row of the SHA3 specification is located in one zmm register.
  BIND(rounds24_loop);
  subl(roundsLeft, 1);

  evmovdquw(xmm5, xmm0, Assembler::AVX_512bit);
  // vpternlogq(x, 150, y, z) does x = x ^ y ^ z
  vpternlogq(xmm5, 150, xmm1, xmm2, Assembler::AVX_512bit);
  vpternlogq(xmm5, 150, xmm3, xmm4, Assembler::AVX_512bit);
  // Now the "c row", i.e. c0-c4 are in zmm5.
  // Rotate each element of the c row by one bit to zmm6, call the
  // rotated version c'.
  evprolq(xmm6, xmm5, 1, Assembler::AVX_512bit);
  // Rotate elementwise the c row so that c4 becomes c0,
  // c0 becomes c1, etc.
  evpermt2q(xmm5, xmm30, xmm5, Assembler::AVX_512bit);
  // rotate elementwise the c' row so that c'0 becomes c'4,
  // c'1 becomes c'0, etc.
  evpermt2q(xmm6, xmm31, xmm6, Assembler::AVX_512bit);
  vpternlogq(xmm0, 150, xmm5, xmm6, Assembler::AVX_512bit);
  vpternlogq(xmm1, 150, xmm5, xmm6, Assembler::AVX_512bit);
  vpternlogq(xmm2, 150, xmm5, xmm6, Assembler::AVX_512bit);
  vpternlogq(xmm3, 150, xmm5, xmm6, Assembler::AVX_512bit);
  vpternlogq(xmm4, 150, xmm5, xmm6, Assembler::AVX_512bit);
  // Now the theta mapping has been finished.

  // Do the cyclical permutation of the 24 moving state elements
  // and the required rotations within each element (the combined
  // rho and sigma steps).
  evpermt2q(xmm4, xmm17, xmm3, Assembler::AVX_512bit);
  evpermt2q(xmm3, xmm18, xmm2, Assembler::AVX_512bit);
  evpermt2q(xmm2, xmm17, xmm1, Assembler::AVX_512bit);
  evpermt2q(xmm1, xmm19, xmm0, Assembler::AVX_512bit);
  evpermt2q(xmm4, xmm20, xmm2, Assembler::AVX_512bit);
  // The 24 moving elements are now in zmm1, zmm3 and zmm4,
  // do the rotations now.
  evprolvq(xmm1, xmm1, xmm27, Assembler::AVX_512bit);
  evprolvq(xmm3, xmm3, xmm28, Assembler::AVX_512bit);
  evprolvq(xmm4, xmm4, xmm29, Assembler::AVX_512bit);
  evmovdquw(xmm2, xmm1, Assembler::AVX_512bit);
  evmovdquw(xmm5, xmm3, Assembler::AVX_512bit);
  evpermt2q(xmm0, xmm21, xmm4, Assembler::AVX_512bit);
  evpermt2q(xmm1, xmm22, xmm3, Assembler::AVX_512bit);
  evpermt2q(xmm5, xmm22, xmm2, Assembler::AVX_512bit);
  evmovdquw(xmm3, xmm1, Assembler::AVX_512bit);
  evmovdquw(xmm2, xmm5, Assembler::AVX_512bit);
  evpermt2q(xmm1, xmm23, xmm4, Assembler::AVX_512bit);
  evpermt2q(xmm2, xmm24, xmm4, Assembler::AVX_512bit);
  evpermt2q(xmm3, xmm25, xmm4, Assembler::AVX_512bit);
  evpermt2q(xmm4, xmm26, xmm5, Assembler::AVX_512bit);
  // The combined rho and sigma steps are done.

  // Do the chi step (the same operation on all 5 rows).
  // vpternlogq(x, 180, y, z) does x = x ^ (y & ~z).
  evpermt2q(xmm5, xmm31, xmm0, Assembler::AVX_512bit);
  evpermt2q(xmm6, xmm31, xmm5, Assembler::AVX_512bit);
  vpternlogq(xmm0, 180, xmm6, xmm5, Assembler::AVX_512bit);

  evpermt2q(xmm5, xmm31, xmm1, Assembler::AVX_512bit);
  evpermt2q(xmm6, xmm31, xmm5, Assembler::AVX_512bit);
  vpternlogq(xmm1, 180, xmm6, xmm5, Assembler::AVX_512bit);

  // xor the round constant into a0 (the lowest 64 bits of zmm0
  evpxorq(xmm0, k1, xmm0, Address(constant2use, 0), true, Assembler::AVX_512bit);
  addptr(constant2use, 8);

  evpermt2q(xmm5, xmm31, xmm2, Assembler::AVX_512bit);
  evpermt2q(xmm6, xmm31, xmm5, Assembler::AVX_512bit);
  vpternlogq(xmm2, 180, xmm6, xmm5, Assembler::AVX_512bit);

  evpermt2q(xmm5, xmm31, xmm3, Assembler::AVX_512bit);
  evpermt2q(xmm6, xmm31, xmm5, Assembler::AVX_512bit);
  vpternlogq(xmm3, 180, xmm6, xmm5, Assembler::AVX_512bit);

  evpermt2q(xmm5, xmm31, xmm4, Assembler::AVX_512bit);
  evpermt2q(xmm6, xmm31, xmm5, Assembler::AVX_512bit);
  vpternlogq(xmm4, 180, xmm6, xmm5, Assembler::AVX_512bit);
  cmpl(roundsLeft, 0);
  jcc(Assembler::notEqual, rounds24_loop);

  if (multiBlock) {
    addptr(buf, block_size);
    addl(ofs, block_size);
    cmpl(ofs, limit);
    jcc(Assembler::lessEqual, sha3_loop);
    movq(rax, ofs); // return ofs
  } else {
    xorq(rax, rax); // return 0
  }

  // store the state
  evmovdquq(Address(state, 0), k5, xmm0, true, Assembler::AVX_512bit);
  evmovdquq(Address(state, 40), k5, xmm1, true, Assembler::AVX_512bit);
  evmovdquq(Address(state, 80), k5, xmm2, true, Assembler::AVX_512bit);
  evmovdquq(Address(state, 120), k5, xmm3, true, Assembler::AVX_512bit);
  evmovdquq(Address(state, 160), k5, xmm4, true, Assembler::AVX_512bit);
}

#endif

# Copyright (c) 2025 2bllw8
# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)

project(anemo_native C)

add_library(anemo_native SHARED native_memory.c)

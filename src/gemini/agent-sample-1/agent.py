"""
AI Agent with Calculator Tool Calling demonstration using Gemini 3.6 Flash.
Logs full request and response payloads on every turn.
"""

import os
import json
import ast
import operator
import math
import httpx
from typing import Union, Dict, Any
from google import genai
from google.genai import types

MODEL_ID = "gemini-3.6-flash"

# ==============================================================================
# 1. Calculator Tools Definition
# ==============================================================================

def add(a: float, b: float) -> float:
    """Adds two numbers together.

    Args:
        a: The first number.
        b: The second number.
    """
    return float(a + b)

def subtract(a: float, b: float) -> float:
    """Subtracts b from a (a - b).

    Args:
        a: The number to subtract from.
        b: The number to subtract.
    """
    return float(a - b)

def multiply(a: float, b: float) -> float:
    """Multiplies two numbers together.

    Args:
        a: The first factor.
        b: The second factor.
    """
    return float(a * b)

def divide(a: float, b: float) -> Union[float, str]:
    """Divides a by b (a / b).

    Args:
        a: The numerator.
        b: The denominator.
    """
    if b == 0:
        return "Error: Division by zero is undefined."
    return float(a / b)

def power(base: float, exponent: float) -> float:
    """Calculates base raised to the power of exponent (base ** exponent).

    Args:
        base: The base number.
        exponent: The exponent power.
    """
    return float(base ** exponent)

# --- Geometry & Order Tools (Useful for Parallel Invocations) ---

def calculate_circle_area(radius: float) -> float:
    """Calculates the area of a circle given its radius in meters.

    Args:
        radius: The circle radius in meters.
    """
    return round(float(math.pi * (radius ** 2)), 4)

def calculate_hypotenuse(a: float, b: float) -> float:
    """Calculates the hypotenuse of a right triangle given legs a and b.

    Args:
        a: Length of side a.
        b: Length of side b.
    """
    return round(float(math.sqrt(a ** 2 + b ** 2)), 4)

def calculate_cylinder_volume(radius: float, height: float) -> float:
    """Calculates the volume of a cylinder given radius and height.

    Args:
        radius: Circular base radius.
        height: Cylinder height.
    """
    return round(float(math.pi * (radius ** 2) * height), 4)

def calculate_line_item_subtotal(item_name: str, quantity: int, unit_price: float) -> Dict[str, Any]:
    """Calculates the subtotal for an order line item.

    Args:
        item_name: Name of the product.
        quantity: Quantity purchased.
        unit_price: Price per unit in USD.
    """
    subtotal = quantity * unit_price
    return {"item_name": item_name, "quantity": quantity, "unit_price": unit_price, "subtotal": round(subtotal, 2)}

# Safe AST-based mathematical expression evaluator
_SAFE_OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.Pow: operator.pow,
    ast.Mod: operator.mod,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}

def _eval_ast(node):
    if isinstance(node, ast.Expression):
        return _eval_ast(node.body)
    elif isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    elif isinstance(node, ast.BinOp):
        left = _eval_ast(node.left)
        right = _eval_ast(node.right)
        op_type = type(node.op)
        if op_type in _SAFE_OPERATORS:
            return _SAFE_OPERATORS[op_type](left, right)
        raise ValueError(f"Unsupported binary operator: {op_type.__name__}")
    elif isinstance(node, ast.UnaryOp):
        operand = _eval_ast(node.operand)
        op_type = type(node.op)
        if op_type in _SAFE_OPERATORS:
            return _SAFE_OPERATORS[op_type](operand)
        raise ValueError(f"Unsupported unary operator: {op_type.__name__}")
    else:
        raise ValueError(f"Unsupported syntax element: {type(node).__name__}")

def calculate_expression(expression: str) -> Union[float, str]:
    """Safely evaluates a full mathematical expression string.
    Supports parentheses, +, -, *, /, **, and %.

    Args:
        expression: The mathematical expression, e.g. '(15 * 24) + (100 / 4)'.
    """
    try:
        clean_expr = expression.strip().replace("^", "**")
        parsed = ast.parse(clean_expr, mode="eval")
        return float(_eval_ast(parsed))
    except Exception as e:
        return f"Error evaluating expression '{expression}': {str(e)}"

TOOL_REGISTRY = {
    "add": add,
    "subtract": subtract,
    "multiply": multiply,
    "divide": divide,
    "power": power,
    "calculate_circle_area": calculate_circle_area,
    "calculate_hypotenuse": calculate_hypotenuse,
    "calculate_cylinder_volume": calculate_cylinder_volume,
    "calculate_line_item_subtotal": calculate_line_item_subtotal,
    "calculate_expression": calculate_expression,
}

TOOLS_LIST = list(TOOL_REGISTRY.values())


# ==============================================================================
# 2. Turn-by-Turn Payload Logger
# ==============================================================================

class PayloadLogger:
    """Helper to format and display API request/response payloads at each turn."""

    @staticmethod
    def log_request(turn: int, contents: list, tools: list, model: str):
        print("\n" + "=" * 80)
        print(f"📤 [TURN {turn}] REQUEST PAYLOAD SENT TO GEMINI ({model})")
        print("=" * 80)
        
        serialized_contents = []
        for c in contents:
            if hasattr(c, "model_dump"):
                serialized_contents.append(c.model_dump(exclude_none=True))
            else:
                serialized_contents.append(str(c))
        
        payload_summary = {
            "model": model,
            "available_tools": [t.__name__ for t in tools],
            "conversation_history": serialized_contents,
        }
        print(json.dumps(payload_summary, indent=2, default=str))

    @staticmethod
    def log_response(turn: int, response):
        print("\n" + "=" * 80)
        print(f"📥 [TURN {turn}] RESPONSE PAYLOAD RECEIVED FROM GEMINI")
        print("=" * 80)
        
        if hasattr(response, "model_dump"):
            resp_dict = response.model_dump(exclude_none=True)
        else:
            resp_dict = str(response)
            
        print(json.dumps(resp_dict, indent=2, default=str))

    @staticmethod
    def log_tool_execution(tool_name: str, args: dict, result):
        print("\n" + "-" * 80)
        print(f"⚙️  [LOCAL TOOL EXECUTION] {tool_name}")
        print(f"   Input Arguments: {json.dumps(args, default=str)}")
        print(f"   Execution Output: {result}")
        print("-" * 80)


# ==============================================================================
# 3. Agent Execution Engine
# ==============================================================================

def run_calculator_agent(
    user_prompt: str,
    client: genai.Client,
    model: str = MODEL_ID,
    max_turns: int = 5,
    verbose: bool = True
) -> str:
    """Runs an AI Agent with calculator tools, logging request and response payloads on every turn."""
    print("\n" + "#" * 80)
    print(f"🚀 STARTING AGENT TASK: \"{user_prompt}\"")
    print("#" * 80)
    
    contents = [
        types.Content(
            role="user",
            parts=[types.Part.from_text(text=user_prompt)]
        )
    ]
    
    config = types.GenerateContentConfig(
        tools=TOOLS_LIST,
        temperature=0.0,
        automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        system_instruction="You are a precise mathematical assistant. Use the provided calculator tools whenever performing calculations rather than calculating mental arithmetic."
    )
    
    turn = 1
    while turn <= max_turns:
        # 1. Log outgoing request payload
        if verbose:
            PayloadLogger.log_request(turn=turn, contents=contents, tools=TOOLS_LIST, model=model)
        
        # 2. Call Gemini
        response = client.models.generate_content(
            model=model,
            contents=contents,
            config=config
        )
        
        # 3. Log incoming response payload
        if verbose:
            PayloadLogger.log_response(turn=turn, response=response)
        
        # 4. Check if final answer reached
        if not response.function_calls:
            print("\n" + "*" * 80)
            print(f"🎯 [AGENT FINAL ANSWER - COMPLETED IN {turn} TURN(S)]:")
            print(response.text)
            print("*" * 80)
            return response.text
        
        # Detect and highlight parallel tool calls
        num_calls = len(response.function_calls)
        if num_calls > 1:
            print("\n" + "⚡" * 40)
            print(f"⚡ [PARALLEL TOOL CALLS DETECTED]: Gemini emitted {num_calls} tool calls in Turn {turn}!")
            for idx, fc in enumerate(response.function_calls, 1):
                print(f"   [{idx}] {fc.name}({fc.args})")
            print("⚡" * 40)
        
        # Append model response to conversation history
        model_content = response.candidates[0].content
        contents.append(model_content)
        
        # 5. Execute each function call locally
        print(f"\n⚙️  [EXECUTING {num_calls} TOOL CALL(S) LOCALLY]")
        tool_response_parts = []
        for fc in response.function_calls:
            tool_name = fc.name
            tool_args = fc.args or {}
            
            if tool_name not in TOOL_REGISTRY:
                result = f"Error: Tool '{tool_name}' not found."
            else:
                try:
                    func = TOOL_REGISTRY[tool_name]
                    result = func(**tool_args)
                except Exception as err:
                    result = f"Error executing {tool_name}: {str(err)}"
            
            PayloadLogger.log_tool_execution(tool_name, tool_args, result)
            
            tool_response_parts.append(
                types.Part.from_function_response(
                    name=tool_name,
                    response={"result": result}
                )
            )
            
        # 6. Append tool responses back to conversation history
        contents.append(
            types.Content(
                role="user",
                parts=tool_response_parts
            )
        )
        
        turn += 1
    
    print("⚠️ Max turns reached without final answer.")
    return "Max turns reached."


if __name__ == "__main__":
    if "GEMINI_API_KEY" not in os.environ or not os.environ["GEMINI_API_KEY"]:
        print("Please set your GEMINI_API_KEY environment variable.")
        print("Example: export GEMINI_API_KEY='your-key-here'")
        exit(1)
        
    client = genai.Client()
    
    # Test sample question
    sample_prompt = (
        "A warehouse has 35 crates of apples. Each crate contains 120 apples. "
        "If 5 crates are spoiled and thrown away, and the remaining apples are distributed "
        "equally among 6 local grocery stores, how many apples does each store get?"
    )
    run_calculator_agent(sample_prompt, client=client)

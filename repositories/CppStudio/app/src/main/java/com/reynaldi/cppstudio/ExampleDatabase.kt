package com.reynaldi.cppstudio

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A single offline example. Code in this catalogue is written for C++ Studio. */
data class CppExample(
    val id: String,
    val category: String,
    val title: String,
    val variant: String,
    val summary: String,
    val code: String,
    val sourceUrls: List<String>
)

/**
 * Built-in, offline C++ example catalogue.
 * Topic coverage is informed by the public topic indexes listed in SOURCES. The source code
 * itself is original project material, not copied from either reference site.
 */
object ExampleDatabase {
    const val VERSION = "1.4"
    const val GEeksFORGEEKS_INDEX = "https://www.geeksforgeeks.org/cpp/cpp-programming-examples/"
    const val PROGRAMIZ_INDEX = "https://www.programiz.com/cpp-programming/examples"
    const val GFG_LINEAR_SEARCH = "https://www.geeksforgeeks.org/dsa/linear-search/"
    const val PROGRAMIZ_LINEAR_SEARCH = "https://www.programiz.com/dsa/linear-search"

    val entries: List<CppExample> = listOf(
        CppExample("hello-world", "Basics", "Hello, World!", "Basic output", "Print a first line of text.", """
#include <iostream>

int main() {
    std::cout << "Hello, World!\n";
    return 0;
}
""".trimIndent() + "\n", listOf(GEeksFORGEEKS_INDEX, PROGRAMIZ_INDEX)),
        CppExample("read-input", "Basics", "Read and display input", "Console input", "Read a value with std::cin and display it.", """
#include <iostream>

int main() {
    int value{};
    std::cout << "Enter an integer: ";
    if (!(std::cin >> value)) {
        std::cerr << "Invalid integer input\n";
        return 1;
    }
    std::cout << "You entered: " << value << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX)),
        CppExample("add-two-numbers", "Basics", "Add two numbers", "Arithmetic", "Read two numbers and print their sum.", """
#include <iostream>

int main() {
    long long a{}, b{};
    if (!(std::cin >> a >> b)) return 1;
    std::cout << a + b << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("quotient-remainder", "Basics", "Quotient and remainder", "Arithmetic", "Use integer division and modulo with a zero check.", """
#include <iostream>

int main() {
    long long a{}, b{};
    if (!(std::cin >> a >> b)) return 1;
    if (b == 0) {
        std::cout << "Division by zero is not allowed\n";
        return 1;
    }
    std::cout << "Quotient: " << a / b << '\n';
    std::cout << "Remainder: " << a % b << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX)),
        CppExample("swap-two-numbers", "Basics", "Swap two numbers", "Variables", "Swap two values using a temporary variable.", """
#include <iostream>

int main() {
    int a{}, b{};
    if (!(std::cin >> a >> b)) return 1;
    int temporary = a;
    a = b;
    b = temporary;
    std::cout << a << ' ' << b << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("even-or-odd", "Decisions and loops", "Check even or odd", "if / else", "Use modulo to decide whether an integer is even.", """
#include <iostream>

int main() {
    long long number{};
    if (!(std::cin >> number)) return 1;
    std::cout << (number % 2 == 0 ? "Even" : "Odd") << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("largest-of-three", "Decisions and loops", "Largest of three numbers", "if / else", "Find the maximum of three input values.", """
#include <algorithm>
#include <iostream>

int main() {
    int a{}, b{}, c{};
    if (!(std::cin >> a >> b >> c)) return 1;
    std::cout << std::max({a, b, c}) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("vowel-or-consonant", "Decisions and loops", "Vowel or consonant", "Character checks", "Classify an English alphabet character.", """
#include <cctype>
#include <iostream>

int main() {
    char ch{};
    if (!(std::cin >> ch)) return 1;
    const char lower = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    if (!std::isalpha(static_cast<unsigned char>(ch))) {
        std::cout << "Not an alphabet\n";
    } else if (lower == 'a' || lower == 'e' || lower == 'i' || lower == 'o' || lower == 'u') {
        std::cout << "Vowel\n";
    } else {
        std::cout << "Consonant\n";
    }
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("leap-year", "Decisions and loops", "Check leap year", "Boolean conditions", "Apply the divisible-by-4, century, and divisible-by-400 rules.", """
#include <iostream>

int main() {
    int year{};
    if (!(std::cin >> year)) return 1;
    const bool leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    std::cout << (leap ? "Leap year" : "Not a leap year") << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("calculator-switch", "Decisions and loops", "Simple calculator", "switch / case", "Calculate with +, -, *, or / and reject division by zero.", """
#include <iostream>

int main() {
    double a{}, b{};
    char operation{};
    if (!(std::cin >> a >> operation >> b)) return 1;
    switch (operation) {
        case '+': std::cout << a + b; break;
        case '-': std::cout << a - b; break;
        case '*': std::cout << a * b; break;
        case '/':
            if (b == 0.0) { std::cout << "Division by zero is not allowed"; return 1; }
            std::cout << a / b;
            break;
        default: std::cout << "Unknown operator"; return 1;
    }
    std::cout << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX)),
        CppExample("multiplication-table", "Decisions and loops", "Multiplication table", "for loop", "Print the first ten multiples of an integer.", """
#include <iostream>

int main() {
    int number{};
    if (!(std::cin >> number)) return 1;
    for (int i = 1; i <= 10; ++i) {
        std::cout << number << " x " << i << " = " << number * i << '\n';
    }
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("sum-natural-numbers", "Decisions and loops", "Sum of natural numbers", "Loop accumulator", "Calculate 1 + 2 + ... + n using a running total.", """
#include <iostream>

int main() {
    long long n{};
    if (!(std::cin >> n) || n < 0) return 1;
    long long total = 0;
    for (long long i = 1; i <= n; ++i) total += i;
    std::cout << total << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("factorial-iterative", "Functions and recursion", "Factorial of a number", "Iterative implementation", "Multiply from 1 through n. This entry uses a loop, not recursion.", """
#include <iostream>

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0 || n > 20) return 1;
    unsigned long long result = 1;
    for (int i = 2; i <= n; ++i) result *= static_cast<unsigned long long>(i);
    std::cout << result << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("factorial-recursive", "Functions and recursion", "Factorial using recursion", "Recursive implementation", "Use a base case and call the same function with n - 1.", """
#include <iostream>

unsigned long long factorial(int n) {
    if (n <= 1) return 1;
    return static_cast<unsigned long long>(n) * factorial(n - 1);
}

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0 || n > 20) return 1;
    std::cout << factorial(n) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("fibonacci-iterative", "Functions and recursion", "Fibonacci sequence", "Iterative implementation", "Generate n Fibonacci values using two rolling variables.", """
#include <iostream>

int main() {
    int count{};
    if (!(std::cin >> count) || count < 0 || count > 90) return 1;
    unsigned long long a = 0, b = 1;
    for (int i = 0; i < count; ++i) {
        std::cout << a << (i + 1 == count ? '\n' : ' ');
        const unsigned long long next = a + b;
        a = b;
        b = next;
    }
    if (count == 0) std::cout << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("fibonacci-recursive", "Functions and recursion", "Fibonacci using recursion", "Recursive implementation", "Define F(0), F(1), then recursively sum the previous two terms.", """
#include <iostream>

long long fibonacci(int n) {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0 || n > 40) return 1;
    std::cout << fibonacci(n) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("prime-check", "Decisions and loops", "Check prime number", "Trial division", "Test divisors only up to the square root of n.", """
#include <iostream>

int main() {
    long long n{};
    if (!(std::cin >> n)) return 1;
    bool isPrime = n >= 2;
    for (long long divisor = 2; divisor <= n / divisor && isPrime; ++divisor) {
        if (n % divisor == 0) isPrime = false;
    }
    std::cout << (isPrime ? "Prime" : "Not prime") << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("gcd-iterative", "Functions and recursion", "GCD of two numbers", "Euclidean loop", "Use repeated remainder operations until the remainder is zero.", """
#include <cstdlib>
#include <iostream>

int main() {
    long long a{}, b{};
    if (!(std::cin >> a >> b)) return 1;
    a = std::llabs(a);
    b = std::llabs(b);
    while (b != 0) {
        const long long remainder = a % b;
        a = b;
        b = remainder;
    }
    std::cout << a << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("gcd-recursive", "Functions and recursion", "GCD using recursion", "Recursive implementation", "Use gcd(a, b) = gcd(b, a % b) with b == 0 as the base case.", """
#include <cstdlib>
#include <iostream>

long long gcd(long long a, long long b) {
    if (b == 0) return std::llabs(a);
    return gcd(b, a % b);
}

int main() {
    long long a{}, b{};
    if (!(std::cin >> a >> b)) return 1;
    std::cout << gcd(a, b) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("lcm", "Functions and recursion", "LCM of two numbers", "GCD-based formula", "Compute |a / gcd(a,b) * b| and handle zero input.", """
#include <cstdlib>
#include <iostream>
#include <numeric>

int main() {
    long long a{}, b{};
    if (!(std::cin >> a >> b)) return 1;
    if (a == 0 || b == 0) {
        std::cout << 0 << '\n';
    } else {
        const long long divisor = std::gcd(a, b);
        std::cout << std::llabs((a / divisor) * b) << '\n';
    }
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("reverse-number", "Decisions and loops", "Reverse an integer", "Digit processing", "Build a reversed value by taking the last digit each time.", """
#include <iostream>
#include <limits>

int main() {
    long long number{};
    if (!(std::cin >> number)) return 1;
    long long remaining = number;
    long long reversed = 0;
    while (remaining != 0) {
        const int digit = static_cast<int>(remaining % 10);
        if (reversed > (std::numeric_limits<long long>::max() - digit) / 10 ||
            reversed < (std::numeric_limits<long long>::min() - digit) / 10) return 1;
        reversed = reversed * 10 + digit;
        remaining /= 10;
    }
    std::cout << reversed << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX)),
        CppExample("palindrome-number", "Decisions and loops", "Palindrome number", "Digit processing", "Compare a non-negative integer with its reverse.", """
#include <iostream>

int main() {
    long long number{};
    if (!(std::cin >> number) || number < 0) return 1;
    long long remaining = number, reversed = 0;
    while (remaining > 0) {
        reversed = reversed * 10 + remaining % 10;
        remaining /= 10;
    }
    std::cout << (number == reversed ? "Palindrome" : "Not palindrome") << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("power-iterative", "Functions and recursion", "Power of a number", "Iterative implementation", "Multiply the base by itself exponent times (non-negative exponent).", """
#include <iostream>

int main() {
    double base{};
    int exponent{};
    if (!(std::cin >> base >> exponent) || exponent < 0) return 1;
    double result = 1.0;
    for (int i = 0; i < exponent; ++i) result *= base;
    std::cout << result << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("power-recursive", "Functions and recursion", "Power using recursion", "Recursive implementation", "Use a base case for exponent zero and reduce the exponent by one.", """
#include <iostream>

double power(double base, unsigned int exponent) {
    if (exponent == 0) return 1.0;
    return base * power(base, exponent - 1);
}

int main() {
    double base{};
    unsigned int exponent{};
    if (!(std::cin >> base >> exponent) || exponent > 10000) return 1;
    std::cout << power(base, exponent) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("array-sum", "Arrays", "Sum array elements", "Array traversal", "Read n values and accumulate their total.", """
#include <iostream>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0 || n > 100000) return 1;
    std::vector<long long> values(n);
    long long total = 0;
    for (auto& value : values) {
        if (!(std::cin >> value)) return 1;
        total += value;
    }
    std::cout << total << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("array-min-max", "Arrays", "Find minimum and maximum", "Array traversal", "Track both extremes while reading an array.", """
#include <iostream>
#include <limits>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n <= 0 || n > 100000) return 1;
    long long minimum = std::numeric_limits<long long>::max();
    long long maximum = std::numeric_limits<long long>::lowest();
    for (int i = 0; i < n; ++i) {
        long long value{};
        if (!(std::cin >> value)) return 1;
        if (value < minimum) minimum = value;
        if (value > maximum) maximum = value;
    }
    std::cout << minimum << ' ' << maximum << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("array-average", "Arrays", "Average of array elements", "Array traversal", "Compute an arithmetic mean using a floating-point result.", """
#include <iomanip>
#include <iostream>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n <= 0 || n > 100000) return 1;
    long double total = 0.0L;
    for (int i = 0; i < n; ++i) {
        long double value{};
        if (!(std::cin >> value)) return 1;
        total += value;
    }
    std::cout << std::fixed << std::setprecision(2) << total / n << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX)),
        CppExample("linear-search-std-find", "Searching", "Linear search", "Using std::find()", "Find an item with the standard algorithm. This variant uses iterators, not a hand-written loop.", """
#include <algorithm>
#include <iostream>
#include <iterator>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    int key{};
    if (!(std::cin >> key)) return 1;

    const auto found = std::find(values.begin(), values.end(), key);
    if (found == values.end()) {
        std::cout << "Not found\n";
    } else {
        std::cout << "Found at index " << std::distance(values.begin(), found) << '\n';
    }
    return 0;
}
""".trimIndent() + "\n", listOf(GFG_LINEAR_SEARCH, PROGRAMIZ_LINEAR_SEARCH)),
        CppExample("linear-search-iterative", "Searching", "Linear search", "Iterative Implementation", "Check elements one by one inside a loop and return the first matching index.", """
#include <iostream>
#include <vector>

int linearSearch(const std::vector<int>& values, int key) {
    for (int i = 0; i < static_cast<int>(values.size()); ++i) {
        if (values[i] == key) return i;
    }
    return -1;
}

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    int key{};
    if (!(std::cin >> key)) return 1;
    std::cout << linearSearch(values, key) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GFG_LINEAR_SEARCH, PROGRAMIZ_LINEAR_SEARCH)),
        CppExample("linear-search-recursive", "Searching", "Linear search", "Recursive Implementation", "Use a base case and recursively advance the current index. This is not the iterative loop version.", """
#include <iostream>
#include <vector>

int linearSearchRecursive(const std::vector<int>& values, int key, std::size_t index = 0) {
    if (index >= values.size()) return -1;
    if (values[index] == key) return static_cast<int>(index);
    return linearSearchRecursive(values, key, index + 1);
}

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    int key{};
    if (!(std::cin >> key)) return 1;
    std::cout << linearSearchRecursive(values, key) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GFG_LINEAR_SEARCH, PROGRAMIZ_LINEAR_SEARCH)),
        CppExample("binary-search-iterative", "Searching", "Binary search", "Iterative implementation", "Search a sorted vector by repeatedly narrowing the low/high range.", """
#include <iostream>
#include <vector>

int binarySearch(const std::vector<int>& values, int key) {
    int low = 0;
    int high = static_cast<int>(values.size()) - 1;
    while (low <= high) {
        const int mid = low + (high - low) / 2;
        if (values[mid] == key) return mid;
        if (values[mid] < key) low = mid + 1;
        else high = mid - 1;
    }
    return -1;
}

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    int key{};
    if (!(std::cin >> key)) return 1;
    std::cout << binarySearch(values, key) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GEeksFORGEEKS_INDEX, PROGRAMIZ_INDEX)),
        CppExample("binary-search-recursive", "Searching", "Binary search", "Recursive implementation", "Call the same search function with a strictly smaller search interval.", """
#include <iostream>
#include <vector>

int binarySearchRecursive(const std::vector<int>& values, int key, int low, int high) {
    if (low > high) return -1;
    const int mid = low + (high - low) / 2;
    if (values[mid] == key) return mid;
    if (values[mid] < key) return binarySearchRecursive(values, key, mid + 1, high);
    return binarySearchRecursive(values, key, low, mid - 1);
}

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    int key{};
    if (!(std::cin >> key)) return 1;
    std::cout << binarySearchRecursive(values, key, 0, n - 1) << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GEeksFORGEEKS_INDEX)),
        CppExample("reverse-array", "Arrays", "Reverse an array", "Two-pointer traversal", "Swap pairs from both ends until the middle is reached.", """
#include <utility>
#include <iostream>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    int left = 0;
    int right = n - 1;
    while (left < right) {
        std::swap(values[left], values[right]);
        ++left;
        --right;
    }
    for (int i = 0; i < n; ++i) std::cout << values[i] << (i + 1 == n ? '\n' : ' ');
    if (n == 0) std::cout << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GEeksFORGEEKS_INDEX, PROGRAMIZ_INDEX)),
        CppExample("bubble-sort", "Sorting", "Bubble sort", "Adjacent-swap implementation", "Compare neighbours and move the largest remaining value to the end.", """
#include <utility>
#include <iostream>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    for (int end = n - 1; end > 0; --end) {
        bool swapped = false;
        for (int j = 0; j < end; ++j) {
            if (values[j] > values[j + 1]) {
                std::swap(values[j], values[j + 1]);
                swapped = true;
            }
        }
        if (!swapped) break;
    }
    for (int i = 0; i < n; ++i) std::cout << values[i] << (i + 1 == n ? '\n' : ' ');
    if (n == 0) std::cout << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GEeksFORGEEKS_INDEX, PROGRAMIZ_INDEX)),
        CppExample("selection-sort", "Sorting", "Selection sort", "Minimum-selection implementation", "Select the smallest remaining element and swap it into place.", """
#include <utility>
#include <iostream>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    for (int i = 0; i < n; ++i) {
        int minimumIndex = i;
        for (int j = i + 1; j < n; ++j) {
            if (values[j] < values[minimumIndex]) minimumIndex = j;
        }
        std::swap(values[i], values[minimumIndex]);
    }
    for (int i = 0; i < n; ++i) std::cout << values[i] << (i + 1 == n ? '\n' : ' ');
    if (n == 0) std::cout << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GEeksFORGEEKS_INDEX, PROGRAMIZ_INDEX)),
        CppExample("insertion-sort", "Sorting", "Insertion sort", "Sorted-prefix implementation", "Insert each next value into its correct position in the sorted prefix.", """
#include <utility>
#include <iostream>
#include <vector>

int main() {
    int n{};
    if (!(std::cin >> n) || n < 0) return 1;
    std::vector<int> values(n);
    for (int& value : values) if (!(std::cin >> value)) return 1;
    for (int i = 1; i < n; ++i) {
        const int current = values[i];
        int j = i - 1;
        while (j >= 0 && values[j] > current) {
            values[j + 1] = values[j];
            --j;
        }
        values[j + 1] = current;
    }
    for (int i = 0; i < n; ++i) std::cout << values[i] << (i + 1 == n ? '\n' : ' ');
    if (n == 0) std::cout << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(GEeksFORGEEKS_INDEX, PROGRAMIZ_INDEX)),
        CppExample("string-length", "Strings", "String length", "std::string", "Read a line and get its number of characters.", """
#include <iostream>
#include <string>

int main() {
    std::string text;
    std::getline(std::cin, text);
    std::cout << text.size() << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("reverse-string", "Strings", "Reverse a string", "Two-pointer string", "Reverse characters in place using std::reverse.", """
#include <algorithm>
#include <iostream>
#include <string>

int main() {
    std::string text;
    std::getline(std::cin, text);
    std::reverse(text.begin(), text.end());
    std::cout << text << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("string-palindrome", "Strings", "Palindrome string", "Two-pointer comparison", "Compare mirrored characters from the start and end.", """
#include <iostream>
#include <string>

int main() {
    std::string text;
    std::getline(std::cin, text);
    bool palindrome = true;
    for (std::size_t i = 0; i < text.size() / 2; ++i) {
        if (text[i] != text[text.size() - 1 - i]) {
            palindrome = false;
            break;
        }
    }
    std::cout << (palindrome ? "Palindrome" : "Not palindrome") << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("count-vowels-consonants", "Strings", "Count vowels and consonants", "Character classification", "Count English letters while ignoring whitespace and punctuation.", """
#include <cctype>
#include <iostream>
#include <string>

int main() {
    std::string text;
    std::getline(std::cin, text);
    int vowels = 0, consonants = 0;
    for (unsigned char ch : text) {
        if (!std::isalpha(ch)) continue;
        const char lower = static_cast<char>(std::tolower(ch));
        if (lower == 'a' || lower == 'e' || lower == 'i' || lower == 'o' || lower == 'u') ++vowels;
        else ++consonants;
    }
    std::cout << vowels << ' ' << consonants << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("matrix-addition", "Matrices", "Add two matrices", "Nested loops", "Add corresponding elements of two matrices with equal dimensions.", """
#include <iostream>
#include <vector>

int main() {
    int rows{}, cols{};
    if (!(std::cin >> rows >> cols) || rows < 0 || cols < 0 || rows > 100 || cols > 100) return 1;
    std::vector<std::vector<long long>> a(rows, std::vector<long long>(cols));
    std::vector<std::vector<long long>> b(rows, std::vector<long long>(cols));
    for (auto& row : a) for (auto& value : row) if (!(std::cin >> value)) return 1;
    for (auto& row : b) for (auto& value : row) if (!(std::cin >> value)) return 1;
    for (int r = 0; r < rows; ++r) {
        for (int c = 0; c < cols; ++c) std::cout << a[r][c] + b[r][c] << (c + 1 == cols ? '\n' : ' ');
        if (cols == 0) std::cout << '\n';
    }
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("matrix-transpose", "Matrices", "Transpose a matrix", "Row / column swap", "Print rows as columns and columns as rows.", """
#include <iostream>
#include <vector>

int main() {
    int rows{}, cols{};
    if (!(std::cin >> rows >> cols) || rows < 0 || cols < 0 || rows > 100 || cols > 100) return 1;
    std::vector<std::vector<int>> matrix(rows, std::vector<int>(cols));
    for (auto& row : matrix) for (int& value : row) if (!(std::cin >> value)) return 1;
    for (int c = 0; c < cols; ++c) {
        for (int r = 0; r < rows; ++r) std::cout << matrix[r][c] << (r + 1 == rows ? '\n' : ' ');
        if (rows == 0) std::cout << '\n';
    }
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("right-triangle-pattern", "Patterns", "Right triangle pattern", "Nested loops", "Print a left-aligned triangle of stars.", """
#include <iostream>

int main() {
    int height{};
    if (!(std::cin >> height) || height < 0 || height > 100) return 1;
    for (int row = 1; row <= height; ++row) {
        for (int col = 0; col < row; ++col) std::cout << '*';
        std::cout << '\n';
    }
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX)),
        CppExample("student-structure", "Structures", "Student record with struct", "User-defined type", "Store and display a student's name, ID, and score.", """
#include <iomanip>
#include <iostream>
#include <string>

struct Student {
    std::string name;
    int id{};
    double score{};
};

int main() {
    Student student;
    std::getline(std::cin, student.name);
    if (!(std::cin >> student.id >> student.score)) return 1;
    std::cout << student.name << '\n' << student.id << '\n'
              << std::fixed << std::setprecision(2) << student.score << '\n';
    return 0;
}
""".trimIndent() + "\n", listOf(PROGRAMIZ_INDEX, GEeksFORGEEKS_INDEX))
    )

    val categories: List<String> = entries.map { it.category }.distinct()

    fun findById(id: String): CppExample? = entries.firstOrNull { it.id == id }

    /** Exports every example as its own .cpp file plus a searchable index and source notes. */
    fun exportZip(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            val index = buildString {
                appendLine("id,category,title,variant,summary,file,reference_urls")
                for (example in entries) {
                    val fileName = "${example.id}.cpp"
                    val fields = listOf(example.id, example.category, example.title, example.variant,
                        example.summary, fileName, example.sourceUrls.joinToString(" | "))
                    appendLine(fields.joinToString(",") { csv(it) })
                    zip.putNextEntry(ZipEntry("examples/$fileName"))
                    zip.write(example.code.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            putText(zip, "index.csv", index)
            putText(zip, "README.txt", """
                C++ Studio ${VERSION} – offline C++ examples

                This archive contains ${entries.size} original C++ example programs.
                Each program is a separate .cpp file in the examples/ folder.
                Open index.csv to search by category, title, and implementation variant.

                The topic coverage is informed by these public example indexes:
                - GeeksforGeeks: $GEeksFORGEEKS_INDEX
                - Programiz: $PROGRAMIZ_INDEX

                Program code in this archive was written for C++ Studio. The reference links are
                provided for further reading; the archive is not a copy of the websites' code.

                Linear Search has three separate implementations in this archive:
                - linear-search-std-find.cpp: Using std::find()
                - linear-search-iterative.cpp: Iterative Implementation
                - linear-search-recursive.cpp: Recursive Implementation

                Analyze identifies the implementation style before running the matching checks.
                It does not convert one Linear Search variant into another.
            """.trimIndent() + "\n")
            putText(zip, "SOURCES.md", """
                # Reference indexes

                - [GeeksforGeeks – C++ Programming Examples]($GEeksFORGEEKS_INDEX)
                - [Programiz – C++ Examples]($PROGRAMIZ_INDEX)
                - [GeeksforGeeks – Linear Search]($GFG_LINEAR_SEARCH)
                - [Programiz – Linear Search]($PROGRAMIZ_LINEAR_SEARCH)

                These pages inform topic coverage and further reading. Example code in this
                database is authored for C++ Studio and is not copied from the reference pages.
            """.trimIndent() + "\n")
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
}

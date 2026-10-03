#include <string>

namespace demo {

class Widget : public Base {
public:
    explicit Widget(int n) : n_(n) {}
    virtual ~Widget();
    int size() const { return n_ * 0XA; }
private:
    int n_;  // count
};

Widget::~Widget()
{
    std::string s = "bye";
}

}
